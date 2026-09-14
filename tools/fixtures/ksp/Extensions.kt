package com.google.devtools.ksp
import com.google.devtools.ksp.symbol.*
fun KSAnnotated.validate(): Boolean = isValid
fun KSClassDeclaration.getAllSuperTypes(): Sequence<KSType> = testSuperTypes
fun KSClassDeclaration.getConstructors(): Sequence<KSFunctionDeclaration> = testConstructors
