package scalafix.nsc

import scala.collection.mutable
import scala.meta.Dialect
import scala.meta.Type
import scala.reflect.internal.util.SourceFile
import scala.{meta => m}
import scalafix.Fixed
import scalafix.Scalafix
import scalafix.ScalafixConfig
import scalafix.rewrite.SemanticApi
import scalafix.util.logger

trait NscSemanticApi extends ReflectToolkit {

  /** The compiler sets different symbols for `PackageDef`s
    * and term names pointing to that package. Get the
    * underlying symbol of `moduleClass` for them to be equal. */
  @inline
  def getUnderlyingPkgSymbol(pkgSym: g.Symbol) = {
    if (!pkgSym.isModule) pkgSym
    else pkgSym.asModule.moduleClass
  }

  /** Keep members that are meaningful for scoping rules. */
  def keepMeaningfulMembers(s: g.Symbol): Boolean =
    !(s.hasPackageFlag) &&
      (s.isModule || s.isClass || s.isValue || s.isAccessor) && !s.isMethod

  @inline
  def mixScopes(sc: g.Scope, sc2: g.Scope) = {
    val mixedScope = sc.cloneScope
    sc2.foreach(s => mixedScope.enterIfNew(s))
    mixedScope
  }

  /** Return a scope with the default packages imported by Scala. */
  def importDefaultPackages(scope: g.Scope) = {
    import g.definitions.{ScalaPackage, PredefModule, JavaLangPackage}
    // Handle packages to import from user-defined compiler flags
    val packagesToImportFrom = {
      if (g.settings.noimports) Nil
      else if (g.settings.nopredef) List(ScalaPackage, JavaLangPackage)
      else List(ScalaPackage, PredefModule, JavaLangPackage)
    }
    packagesToImportFrom.foldLeft(scope) {
      case (scope, pkg) => mixScopes(scope, pkg.info.members)
    }
  }

  /** Traverse the tree and create the scopes based on packages and imports. */
  private class ScopeTraverser extends g.Traverser {
    val topLevelPkg: g.Symbol = g.rootMirror.RootPackage
    // Don't introduce fully qualified scopes to cause lookup failure
    val topLevelScope = importDefaultPackages(g.newScope)
    val scopes = mutable.Map[g.Symbol, g.Scope](topLevelPkg -> topLevelScope)
    val renames = mutable.Map[g.Symbol, g.Symbol]()
    var enclosingScope = topLevelScope

    def getScope(sym: g.Symbol): g.Scope = {
      scopes.getOrElseUpdate(sym, {
        scopes
          .get(sym.owner)
          .map(_.cloneScope)
          .getOrElse(topLevelScope)
      })
    }

    @inline
    def addAll(members: g.Scope, scope: g.Scope): g.Scope = {
      members
        .filterNot(s => s.isRoot || s.hasPackageFlag)
        .foreach(scope.enterIfNew)
      scope
    }

    @inline
    def addRename(symbol: g.Symbol, renamedTo: g.Name) = {
      val renamedSymbol = symbol.cloneSymbol.setName(renamedTo)
      renames += symbol -> renamedSymbol
    }

    /** Get the underlying type if symbol represents a type alias. */
    @inline
    def getUnderlyingTypeAlias(symbol: g.Symbol) =
      symbol.info.dealias.typeSymbol

    override def traverse(t: g.Tree): Unit = {
      t match {
        case pkg: g.PackageDef =>
          val sym = getUnderlyingPkgSymbol(pkg.pid.symbol)
          val currentScope = getScope(sym)
          currentScope.enterIfNew(sym)

          // Add members when processing the packages globally
          val members = sym.info.members.filter(keepMeaningfulMembers)
          members.foreach(currentScope.enterIfNew)

          // Set enclosing package before visiting enclosed trees
          val previousScope = enclosingScope
          enclosingScope = currentScope
          super.traverse(t)
          enclosingScope = previousScope

        case g.Import(pkg, selectors) =>
          val pkgSym = pkg.symbol

          // Add imported members at the right scope
          // TODO(jvican): Put this into the selectors.foreach
          val importedNames = selectors.map(_.name.decode).toSet
          val imported = pkgSym.info.members.filter(m =>
            importedNames.contains(m.name.decode))
          addAll(imported, enclosingScope)

          selectors.foreach {
            case g.ImportSelector(g.TermName("_"), _, null, _) =>
              addAll(pkgSym.info.members, enclosingScope)
            case g.ImportSelector(from, _, g.TermName("_"), _) =>
              // Look up symbol and unlink it from the scope
              val symbol = enclosingScope.lookup(from)
              if (symbol.exists)
                enclosingScope.unlink(symbol)
            case isel @ g.ImportSelector(from, _, to, _) if to != null =>
              // Look up symbol for import selectors and rename it
              val termSymbol = enclosingScope.lookup(from.toTermName)
              val typeSymbol = getUnderlyingTypeAlias(enclosingScope.lookup(from.toTypeName))
              val existsTerm = termSymbol.exists
              val existsType = typeSymbol.exists

              // Add rename for both term and type name
              if (existsTerm && existsType) {
                addRename(termSymbol, to)
                addRename(typeSymbol, to)
              } else if (existsTerm) {
                addRename(termSymbol, to)
              } else if (existsType) {
                addRename(typeSymbol, to)
              } // TODO(jvican): Warn user that rename was not found
            case _ =>
          }
          super.traverse(t)
        case _ => super.traverse(t)
      }
    }
  }

  private class OffsetTraverser extends g.Traverser {
    val offsets = mutable.Map[Int, g.Tree]()
    val treeOwners = mutable.Map[Int, g.Tree]()
    override def traverse(t: g.Tree): Unit = {
      t match {
        case g.ValDef(_, name, tpt, _) if tpt.nonEmpty =>
          offsets += (tpt.pos.point -> tpt)
          treeOwners += (t.pos.point -> t)
        case g.DefDef(_, _, _, _, tpt, _) =>
          offsets += (tpt.pos.point -> tpt)
          treeOwners += (t.pos.point -> t)
        case _ => super.traverse(t)
      }
    }
  }

  private def getSemanticApi(unit: g.CompilationUnit,
                             config: ScalafixConfig): SemanticApi = {
    if (!g.settings.Yrangepos.value) {
      val instructions = "Please re-compile with the scalac option -Yrangepos enabled"
      val explanation  = "This option is necessary for the semantic API to function"
      sys.error(s"$instructions. $explanation")
    }

    // Compute scopes for global and local imports
    val st = new ScopeTraverser
    st.traverse(unit.body)
    val rootPkg = st.topLevelPkg
    val rootImported = st.scopes(rootPkg)
    val realRootScope = rootPkg.info.members

    // Compute offsets for the whole compilation unit
    val traverser = new OffsetTraverser
    traverser.traverse(unit.body)

    def toMetaType(tp: g.Tree) =
      config.dialect(tp.toString).parse[m.Type].get

    def gimmePosition(t: m.Tree): m.Position = {
      t match {
        case m.Defn.Val(_, Seq(pat), _, _) => pat.pos
        case m.Defn.Def(_, name, _, _, _, _) => name.pos
        case _ => t.pos
      }
    }

    /** Strip `this` references and convert indeterminate names to term names. */
    def stripThis(ref: m.Tree): m.Ref = {
      val transformedTree = ref.transform {
        case m.Term.Select(m.Term.This(ind: m.Name.Indeterminate), name) =>
          m.Term.Select(m.Term.Name(ind.value), name)
        case m.Type.Select(m.Term.This(ind: m.Name.Indeterminate), name) =>
          m.Type.Select(m.Term.Name(ind.value), name)
      }
      transformedTree.asInstanceOf[m.Ref]
    }

    /** Look up a Meta name for both Reflect Term and Type names.
      *
      * Unfortunately, meta selection chains are term refs while they could
      * be type refs (e.g. `A` in `A.this.B` is a term). For this reason,
      * we need to check both names to guarantee whether a name is in scope.
      */
    @inline
    def lookupBothNames(name: String,
                        in: g.Scope,
                        disambiguatingOwner: Option[g.Symbol],
                        disambiguatingNamespace: String): g.Symbol = {
      val typeName = g.TypeName(name)
      val typeNameLookup = in.lookup(typeName)
      val symbol =
        if (typeNameLookup.exists) typeNameLookup
        else {
          val termName = g.TermName(name)
          val termNameLookup = in.lookup(termName)
          if (termNameLookup.exists) termNameLookup
          else g.NoSymbol
        }

      // Disambiguate overloaded symbols caused by name shadowing
      if (symbol.isOverloaded) {
        val alternatives = symbol.alternatives
        disambiguatingOwner
          .flatMap(o => alternatives.find(_.owner == o))
          .getOrElse {
            val substrings = alternatives.iterator
              .filter(s => disambiguatingNamespace.indexOf(s.fullName) == 0)
            // Last effort to disambiguate, pick sym with longest substring
            if (substrings.isEmpty) alternatives.head
            else substrings.maxBy(_.fullName.length)
          }
      } else symbol
    }

    /** Remove sequential prefixes from a concrete ref. */
    def removePrefixes(ref: m.Ref, prefixes: List[m.Name]): m.Ref = {
      /* Pattern match on `value`s of names b/c `stripThis` creates new names. */
      def loop(ref: m.Term.Ref,
               reversedPrefixes: List[m.Name]): List[m.Term.Ref] = {
        reversedPrefixes match {
          case prefix :: acc =>
            val prefixValue = prefix.value
            ref match {
              case m.Term.Select(qual, name) =>
                val qualAsRef = qual.asInstanceOf[m.Term.Ref]
                if (name.value == prefixValue) loop(qualAsRef, acc)
                else {
                  // Make sure that removes names seq and reconstruct trees
                  val nestedResult = loop(qualAsRef, reversedPrefixes)
                  if (nestedResult.isEmpty) List(name)
                  else List(m.Term.Select(nestedResult.head, name))
                }
              case name: m.Term.Name if name.value == prefixValue => Nil
              case r => List(r)
            }
          case Nil => List(ref)
        }
      }

      val transformedRef = ref.transform {
        case m.Type.Select(qual, typeName) =>
          val removed = loop(qual, prefixes.reverse)
          if (removed.isEmpty) typeName
          else m.Type.Select(removed.head, typeName)
        case r => r
      }

      transformedRef.asInstanceOf[m.Ref]
    }

    def lookUpSymbols(names: List[m.Name], scope: g.Scope, from: String) = {
      val (_, reversedSymbols) = {
        names.foldLeft(scope -> List.empty[g.Symbol]) {
          case ((scope, symbols), metaName) =>
            val sym =
              lookupBothNames(metaName.value, scope, symbols.headOption, from)
            if (!sym.exists) scope -> symbols
            else sym.info.members -> (sym :: symbols)
        }
      }
      reversedSymbols.reverse
    }

    /** Rename a type based on used import selectors. */
    def renameType(toRename: m.Ref, renames: Map[m.Name, g.Name]) = {
      toRename.transform {
        case name: m.Name =>
          renames.get(name) match {
            case Some(gname) =>
              val realName = gname.decoded
              if (gname.isTypeName) m.Type.Name(realName)
              else m.Term.Name(realName)
            case None => name
          }
      }
    }

    /** Convert list of names to Term names. */
    def toTermNames(names: List[m.Name]): List[m.Term.Name] = {
      names.map {
        case tn: m.Term.Name => tn
        case name: m.Name => m.Term.Name(name.value)
      }
    }

    /** Get the shortened type `ref` at a concrete spot. */
    def getMissingOrHit(toShorten: m.Ref,
                        inScope: g.Scope,
                        enclosingTerm: g.Symbol): m.Ref = {

      val refNoThis = stripThis(toShorten)
      val names = refNoThis.collect {
        case tn: m.Term.Name => tn
        case tn: m.Type.Name => tn
      }

      // Mix local scope with root scope for FQN and non-FQN lookups
      val wholeScope = mixScopes(inScope, realRootScope)
      val symbols = lookUpSymbols(names, wholeScope, toShorten.syntax)
      val metaToSymbols = names.zip(symbols)
      logger.elem(metaToSymbols)

      if (symbols.nonEmpty) {
        /* Check for path dependent types:
         * 1. Locate the term among the FQN
         * 2. If it exists, get the first value in the chain */
        val maybePathDependentType = metaToSymbols
          .find(ms => ms._2.isAccessor)
          .flatMap(_ =>
            metaToSymbols.dropWhile(ms => !ms._2.isValue).headOption)
        val isPathDependent = maybePathDependentType.isDefined

        val (lastName, lastSymbol) = maybePathDependentType
          .getOrElse(metaToSymbols.last)
        val (onlyPaths, shortenedNames) =
          metaToSymbols.span(_._1 != lastName)

        // Build map of meta names to reflect names
        val renames: Map[m.Name, g.Name] = shortenedNames.map {
          case (metaName, symbol) =>
            val mappedSym = st.renames.getOrElse(symbol, symbol)
            metaName -> mappedSym.name
        }.toMap

        val localSym = inScope.lookup(lastSymbol.name)
        if (lastSymbol.exists &&
            (isPathDependent || localSym.exists)) {
          // Return shortened type for names already in scope
          val onlyNames = onlyPaths.map(_._1)
          val removed = removePrefixes(refNoThis, onlyNames)
          val shortenedAndRenamedType = renameType(removed, renames)
          shortenedAndRenamedType.asInstanceOf[m.Ref]
        } else {
          val (validRefs, invalidRefs) = onlyPaths.span(_._2.exists)
          val onlyShortenedNames = shortenedNames.map(_._1)
          val shortenedRefs = {
            if (validRefs.nonEmpty) {
              val lastValidRef = validRefs.last._1
              lastValidRef :: onlyShortenedNames
            } else onlyShortenedNames
          }

          val termRefs = toTermNames(shortenedRefs.init)
          val typeRef = shortenedRefs.last.asInstanceOf[m.Type.Name]
          val termSelects = termRefs.reduceLeft[m.Term.Ref] {
            case (qual: m.Term, path: m.Term.Name) =>
              m.Term.Select(qual, path)
          }

          m.Type.Select(termSelects, typeRef)
        }
        // Received type is not valid/doesn't exist, return what we got
      } else refNoThis
    }

    new SemanticApi {
      override def shortenType(tpe: m.Type, owner: m.Tree): m.Type = {
        logger.elem(tpe)
        val ownerTpePos = gimmePosition(owner).start.offset
        val ownerTree = traverser.treeOwners(ownerTpePos)
        val gtpeTree = traverser.offsets(ownerTpePos)
        val ownerSymbol = ownerTree.symbol
        val contextOwnerChain = ownerSymbol.ownerChain

        val enclosingPkg =
          contextOwnerChain
            .find(s => s.hasPackageFlag)
            .getOrElse(rootPkg)

        val userImportsScope = {
          if (enclosingPkg == rootPkg) rootImported
          else st.scopes.getOrElse(enclosingPkg, rootImported)
        }

        // Prune owners and use them to create a local bottom up scope
        val interestingOwners =
          contextOwnerChain.takeWhile(_ != enclosingPkg)
        val bottomUpScope = interestingOwners.iterator
          .map(_.info.members.filter(keepMeaningfulMembers))
          .reduce(mixScopes _)
        val globalScope = mixScopes(bottomUpScope, userImportsScope)

        // Get only the type Select chains (that inside have terms)
        val typeRefs = tpe.collect { case ts: m.Type.Select => ts }

        // Collect rewrites to be applied on type refs
        val typeRewrites =
          typeRefs.map(tr => getMissingOrHit(tr, globalScope, enclosingPkg))
        val mappedRewrites = typeRefs.zip(typeRewrites).toMap

        // Replace the types in one transform to not change ref equality
        val shortenedType =
          tpe.transform { case ref: m.Type.Select => mappedRewrites(ref) }
        shortenedType.asInstanceOf[m.Type]
      }

      override def typeSignature(defn: m.Defn): Option[m.Type] = {
        defn match {
          case m.Defn.Val(_, Seq(pat), _, _) =>
            traverser.offsets.get(pat.pos.start.offset).map(toMetaType)
          case m.Defn.Def(_, name, _, _, _, _) =>
            traverser.offsets.get(name.pos.start.offset).map(toMetaType)
          case _ => None
        }
      }
    }
  }

  private def getMetaInput(source: SourceFile): m.Input = {
    if (source.file.file != null && source.file.file.isFile)
      m.Input.File(source.file.file)
    else m.Input.String(new String(source.content))
  }

  def fix(unit: g.CompilationUnit, config: ScalafixConfig): Fixed = {
    val api = getSemanticApi(unit, config)
    val input = getMetaInput(unit.source)
    Scalafix.fix(input, config, Some(api))
  }
}
