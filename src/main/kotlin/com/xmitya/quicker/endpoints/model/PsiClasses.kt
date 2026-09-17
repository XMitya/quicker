package com.xmitya.quicker.endpoints.model

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiFile

/**
 * Every class declared in a file, nested ones included.
 *
 * Goes through [PsiClassOwner] rather than walking the tree for [PsiClass] children: a Kotlin file
 * holds `KtClass` nodes, and its `PsiClass` view exists only as light classes reachable this way. A
 * tree walk silently finds nothing there, which looks exactly like a project with no controllers.
 */
fun classesIn(file: PsiFile): List<PsiClass> {
    val owner = file as? PsiClassOwner ?: return emptyList()
    val out = ArrayList<PsiClass>()
    fun collect(cls: PsiClass) {
        out += cls
        cls.innerClasses.forEach(::collect)
    }
    owner.classes.forEach(::collect)
    return out
}
