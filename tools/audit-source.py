#!/usr/bin/env python3
"""Static source/layout consistency only. Never a substitute for compilation or a device test."""
from pathlib import Path
import re, sys, tomllib, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
skip={'build','.gradle','.git','__pycache__'}
files=[p for p in root.rglob('*') if p.is_file() and not skip.intersection(p.relative_to(root).parts)]
production=[p for p in files if p.suffix=='.kt' and '/src/main/' in '/'+p.relative_to(root).as_posix()]
issues=[]
for p in production:
    text=p.read_text(encoding='utf-8'); rel=p.relative_to(root).as_posix()
    match=re.search(r'^package (\S+)',text,re.M)
    if not match or not match.group(1).startswith('hk.uwu.roxyhook'): issues.append(f'Unexpected production package: {rel}')
    if re.search(r'\bTODO\s*\(|NotImplementedError\s*\(',text): issues.append(f'Production placeholder: {rel}')
    if rel.startswith('roxy-core/') and re.search(r'^import (android\.|io\.github\.libxposed\.|de\.robv\.android\.xposed\.)',text,re.M): issues.append(f'Platform import in core: {rel}')
    if len(text.splitlines())>200: issues.append(f'Production Kotlin file exceeds 200 lines: {rel}')
for p in files:
    if p.name=='AndroidManifest.xml':
        try: ET.parse(p)
        except ET.ParseError as e: issues.append(f'Invalid manifest {p}: {e}')
    if p.name.endswith('.gradle.kts'):
        s=p.read_text(encoding='utf-8')
        if re.search(r'project\(["\']:roxy-(?:kavaref|platforms:libxposed-service)',s): issues.append(f'Removed dependency: {p}')
        if 'tools/fixtures' in s or 'build/signature-check' in s: issues.append(f'Test signature path in production Gradle configuration: {p}')
for removed in ['roxy-kavaref','roxy-platforms/libxposed-service']:
    if (root/removed).exists(): issues.append(f'Removed module still exists: {removed}')
meta=root/'samples/demo-module/src/main/resources/META-INF/xposed'
if meta.exists(): issues.append('Sample has manual Xposed metadata alongside KSP/plugin generation')
required={
'roxy-core/build.gradle.kts':['api(project(":roxy-annotations"))','api(libs.kavaref.core)','api(libs.kavaref.extension)'],
'roxy-platforms/libxposed/build.gradle.kts':['compileOnly(libs.libxposed.api)','api(libs.libxposed.service)'],
'settings.gradle.kts':['includeBuild("roxy-gradle-plugin")', '":roxy-ksp"', '":roxy-annotations"'],
'roxy-gradle-plugin/src/main/kotlin/hk/uwu/roxyhook/gradle/RoxyGradlePlugin.kt':['sources.resources','sources.keepRules','addGeneratedSourceDirectory','com.google.devtools.ksp'],
'roxy-ksp/src/main/kotlin/hk/uwu/roxyhook/ksp/RoxySymbolProcessor.kt':['override fun process','override fun finish','createNewFileByPath','java_init.list'],
}
for name, tokens in required.items():
    text=(root/name).read_text(encoding='utf-8')
    for token in tokens:
        if token not in text: issues.append(f'Missing {token!r} in {name}')
spi=root/'roxy-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider'
if not spi.exists() or spi.read_text(encoding='utf-8').strip()!='hk.uwu.roxyhook.ksp.RoxySymbolProcessorProvider': issues.append('KSP service-provider registration is missing or wrong')
versions=tomllib.loads((root/'gradle/libs.versions.toml').read_text(encoding='utf-8'))['versions']
for name,wanted in {'agp':'9.3.2','kotlin':'2.4.10','ksp':'2.3.9','kavaref':'1.1.0','libxposed':'102.0.0'}.items():
    if versions.get(name)!=wanted: issues.append(f'Unexpected documented version pin: {name}')
for p in files:
    if p.suffix=='.md':
        for target in re.findall(r'\]\(([^\s)]+)\)',p.read_text(encoding='utf-8')):
            target=target.split('#',1)[0]
            if target and '://' not in target and not target.startswith('mailto:') and not (p.parent/target).exists(): issues.append(f'Broken local document link {p}: {target}')
production_line_counts = [len(p.read_text(encoding='utf-8').splitlines()) for p in production]
print(f'Checked {len(production)} production Kotlin files ({sum(production_line_counts)} lines), manifests, module boundaries, SPI, metadata wiring and version pins.')
print(f'Largest production Kotlin file: {max(production_line_counts)} lines.')
if issues:
    print('\n'.join('FAIL  '+x for x in issues)); sys.exit(1)
print('SOURCE AUDIT PASSED. Static consistency only; not a compiler/SDK/Gradle/device result.')
