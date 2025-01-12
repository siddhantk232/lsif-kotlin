package com.sourcegraph.semanticdb_kotlinc

import com.sourcegraph.semanticdb_kotlinc.Semanticdb.SymbolOccurrence.Role
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import java.nio.file.Path
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class SemanticdbVisitor(
    sourceroot: Path,
    private val resolver: DescriptorResolver,
    private val file: KtFile,
    private val lineMap: LineMap,
    globals: GlobalSymbolsCache,
    locals: LocalSymbolsCache = LocalSymbolsCache()
): KtTreeVisitorVoid() {
    private val cache = SymbolsCache(globals, locals)
    private val documentBuilder = SemanticdbTextDocumentBuilder(sourceroot, file, lineMap, cache)

    private data class SymbolDescriptorPair(val symbol: Symbol, val descriptor: DeclarationDescriptor)

    fun build(): Semanticdb.TextDocument {
        super.visitKtFile(file)
        return documentBuilder.build()
    }

    private fun Sequence<SymbolDescriptorPair>?.emitAll(element: PsiElement, role: Role): List<Symbol>? = this?.onEach { (symbol, descriptor) ->
        documentBuilder.emitSemanticdbData(symbol, descriptor, element, role)
    }?.map { it.symbol }?.toList()

    private fun Sequence<Symbol>.with(descriptor: DeclarationDescriptor) = this.map { SymbolDescriptorPair(it, descriptor) }

    override fun visitKtElement(element: KtElement) {
        try {
            super.visitKtElement(element)
        } catch (e: VisitorException) {
            throw e
        } catch (e: Exception) {
            throw VisitorException("exception throw when visiting ${element::class} in ${file.virtualFilePath}: (${lineMap.lineNumber(element)}, ${lineMap.startCharacter(element)})", e)
        }
    }

    override fun visitClass(klass: KtClass) {
        val desc = resolver.fromDeclaration(klass).single()
        var symbols = cache[desc].with(desc).emitAll(klass, Role.DEFINITION)
        if (!klass.hasExplicitPrimaryConstructor()) {
            resolver.syntheticConstructor(klass)?.apply {
                symbols = cache[this].with(this).emitAll(klass, Role.DEFINITION)
            }
        }
        super.visitClass(klass)
    }

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
        val desc = resolver.fromDeclaration(constructor).single()
        // if the constructor is not denoted by the 'constructor' keyword, we want to link it to the class ident
        val symbols = if (!constructor.hasConstructorKeyword()) {
            cache[desc].with(desc).emitAll(constructor.containingClass()!!, Role.DEFINITION)
        } else {
            cache[desc].with(desc).emitAll(constructor.getConstructorKeyword()!!, Role.DEFINITION)
        }
        println("PRIMARY CONSTRUCTOR ${constructor.identifyingElement?.parent ?: constructor.containingClass()} ${desc.name} $symbols")
        super.visitPrimaryConstructor(constructor)
    }

    override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
        val desc = resolver.fromDeclaration(constructor).single()
        val symbols = cache[desc].with(desc).emitAll(constructor.getConstructorKeyword(), Role.DEFINITION)
        super.visitSecondaryConstructor(constructor)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        val desc = resolver.fromDeclaration(function).single()
        val symbols = cache[desc].with(desc).emitAll(function, Role.DEFINITION)
        super.visitNamedFunction(function)
    }

    override fun visitProperty(property: KtProperty) {
        val desc = resolver.fromDeclaration(property).single()
        val symbols = cache[desc].with(desc).emitAll(property, Role.DEFINITION)
        super.visitProperty(property)
    }

    override fun visitParameter(parameter: KtParameter) {
        val symbols = resolver.fromDeclaration(parameter).flatMap { desc ->
            cache[desc].with(desc)
        }.emitAll(parameter, Role.DEFINITION)
        println("NAMED PARAM $parameter $symbols")
        super.visitParameter(parameter)
    }

    override fun visitTypeParameter(parameter: KtTypeParameter) {
        val desc = resolver.fromDeclaration(parameter).single()
        val symbols = cache[desc].with(desc).emitAll(parameter, Role.DEFINITION)
        super.visitTypeParameter(parameter)
    }

    override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        val desc = resolver.fromDeclaration(typeAlias).single()
        val symbols = cache[desc].with(desc).emitAll(typeAlias, Role.DEFINITION)
        super.visitTypeAlias(typeAlias)
    }

    override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
        val desc = resolver.fromDeclaration(accessor).single()
        val symbols = cache[desc].with(desc).emitAll(accessor, Role.DEFINITION)
        super.visitPropertyAccessor(accessor)
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        val desc = resolver.fromReference(expression) ?: run {
            println("NULL DESCRIPTOR FROM NAME EXPRESSION $expression ${expression.javaClass}")
            super.visitSimpleNameExpression(expression)
            return
        }
        val symbols = cache[desc].with(desc).emitAll(expression, Role.REFERENCE)
        super.visitSimpleNameExpression(expression)
    }
}

class VisitorException(msg: String, throwable: Throwable): Exception(msg, throwable)