#!/usr/bin/env python3
"""Generate handwritten COMPILE-ONLY fixtures, never real SDKs or dependencies.
These declarations approximate the used public signatures from docs/SOURCES.md.
Production Gradle source sets never include them. All callable Java bodies throw.
This check catches Kotlin interop/DSL errors, NOT binary/Android compatibility.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1]); root.mkdir(parents=True, exist_ok=True)
files = {}
def java(name, body):
    package, _, simple = name.rpartition('.')
    files[name.replace('.', '/') + '.java'] = f'package {package};\n' + body
T = 'throw new UnsupportedOperationException("COMPILE-ONLY SIGNATURE FIXTURE");'
java('android.content.SharedPreferences', '''public interface SharedPreferences {
 boolean contains(String key); boolean getBoolean(String key,boolean fallback); int getInt(String key,int fallback);
 long getLong(String key,long fallback); float getFloat(String key,float fallback); String getString(String key,String fallback);
 java.util.Set<String> getStringSet(String key,java.util.Set<String> fallback);
 void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener); Editor edit();
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences prefs,String key); }
 interface Editor { Editor putBoolean(String key,boolean value); Editor putInt(String key,int value); Editor putLong(String key,long value);
 Editor putFloat(String key,float value); Editor putString(String key,String value); Editor putStringSet(String key,java.util.Set<String> value);
 Editor remove(String key); Editor clear(); void apply(); boolean commit(); }
}''')
java('android.content.Context', f'public class Context {{ public String getPackageName() {{ {T} }} }}')
java('android.content.DialogInterface', 'public interface DialogInterface { interface OnClickListener { void onClick(DialogInterface dialog,int which); } }')
java('android.os.Bundle', 'public class Bundle {}')
java('android.os.Looper', f'public class Looper {{ public static Looper getMainLooper() {{ {T} }} }}')
java('android.os.Handler', f'public class Handler {{ public Handler(Looper looper) {{ {T} }} public boolean post(Runnable action) {{ {T} }} }}')
java('android.os.ParcelFileDescriptor', f'''public class ParcelFileDescriptor implements java.io.Closeable {{
 public void close() {{ {T} }} public static class AutoCloseInputStream extends java.io.InputStream {{
 public AutoCloseInputStream(ParcelFileDescriptor fd) {{ {T} }} public int read() {{ {T} }} }} }}''')
java('android.util.Log', f'''public class Log {{ public static final int DEBUG=3, INFO=4, WARN=5, ERROR=6;
 public static int e(String tag,String message,Throwable error) {{ {T} }} }}''')
java('android.app.Application', 'public class Application extends android.content.Context {}')
java('android.app.Instrumentation', f'public class Instrumentation {{ public void callApplicationOnCreate(Application application) {{ {T} }} }}')
java('android.view.View', f'''public class View {{ public View(android.content.Context context) {{ {T} }}
 public void setEnabled(boolean value) {{ {T} }} public boolean isEnabled() {{ {T} }}
 public void setOnClickListener(OnClickListener listener) {{ {T} }} public void setPadding(int a,int b,int c,int d) {{ {T} }}
 public interface OnClickListener {{ void onClick(View view); }} }}''')
java('android.widget.TextView', f'''public class TextView extends android.view.View {{ public TextView(android.content.Context context) {{ super(context); }}
 public CharSequence getText() {{ {T} }} public void setText(CharSequence text) {{ {T} }}
 public float getTextSize() {{ {T} }} public void setTextSize(float size) {{ {T} }} }}''')
java('android.widget.Button', 'public class Button extends TextView { public Button(android.content.Context c) { super(c); } }')
java('android.widget.LinearLayout', f'''public class LinearLayout extends android.view.View {{ public static final int VERTICAL=1;
 public LinearLayout(android.content.Context c) {{ super(c); }} public int getOrientation() {{ {T} }}
 public void setOrientation(int value) {{ {T} }} public void addView(android.view.View v) {{ {T} }} }}''')
java('android.app.Activity', f'''public class Activity extends android.content.Context {{
 protected void onCreate(android.os.Bundle saved) {{ {T} }} protected void onResume() {{ {T} }} protected void onDestroy() {{ {T} }}
 public void setContentView(android.view.View view) {{ {T} }} }}''')
java('android.app.AlertDialog', f'''public class AlertDialog {{ public static class Builder {{
 public Builder(android.content.Context c) {{ {T} }} public Builder setTitle(CharSequence title) {{ {T} }}
 public Builder setItems(CharSequence[] items,android.content.DialogInterface.OnClickListener listener) {{ {T} }}
 public AlertDialog show() {{ {T} }} }} }}''')
java('io.github.libxposed.api.XposedInterface', '''import java.lang.reflect.*;
public interface XposedInterface {
 long PROP_CAP_REMOTE=2L; int getApiVersion(); String getFrameworkName(); String getFrameworkVersion(); long getFrameworkProperties();
 interface Invoker<T extends Invoker<T,U>,U extends Executable> {
  sealed interface Type permits Type.Origin,Type.Chain {
   Origin ORIGIN=new Origin(); record Origin() implements Type {} record Chain(int maxPriority) implements Type {}
  }
  T setType(Type type);
  Object invoke(Object receiver,Object...args) throws InvocationTargetException,IllegalArgumentException,IllegalAccessException;
  Object invokeSpecial(Object receiver,Object...args) throws InvocationTargetException,IllegalArgumentException,IllegalAccessException;
 }
 interface CtorInvoker<T> extends Invoker<CtorInvoker<T>,Constructor<T>> {}
 interface Chain { Executable getExecutable(); Object getThisObject(); java.util.List<Object> getArgs();
  Object proceed(Object[] args) throws Throwable; Object proceed() throws Throwable; }
 interface Hooker { Object intercept(Chain chain) throws Throwable; }
 interface HookHandle { Executable getExecutable(); String getId(); void unhook(); HookHandle replaceHook(Hooker hooker); }
 enum ExceptionMode { DEFAULT,PROTECTIVE,PASSTHROUGH }
 interface HookBuilder { HookBuilder setPriority(int value); HookBuilder setId(String id); HookBuilder setExceptionMode(ExceptionMode mode);
  HookHandle intercept(Hooker hooker); }
 HookBuilder hook(Executable member); HookBuilder hookClassInitializer(Class<?> type); boolean deoptimize(Executable member);
 Invoker<?,Method> getInvoker(Method method); <T> CtorInvoker<T> getInvoker(Constructor<T> ctor);
 void log(int priority,String tag,String message,Throwable error);
 android.content.SharedPreferences getRemotePreferences(String group);
 String[] listRemoteFiles(); android.os.ParcelFileDescriptor openRemoteFile(String name);
}''')
java('io.github.libxposed.api.XposedModuleInterface', f'''public interface XposedModuleInterface {{
 interface ModuleLoadedParam {{ String getProcessName(); boolean isSystemServer(); }}
 interface PackageLoadedParam {{ String getPackageName(); boolean isFirstPackage(); ClassLoader getDefaultClassLoader(); }}
 interface PackageReadyParam extends PackageLoadedParam {{ ClassLoader getClassLoader(); }}
 interface SystemServerStartingParam {{ ClassLoader getClassLoader(); }}
 interface HotReloadingParam {{ android.os.Bundle getExtras(); void setSavedInstanceState(Object state); }}
 interface HotReloadedParam extends ModuleLoadedParam {{ android.os.Bundle getExtras(); Object getSavedInstanceState();
 java.util.List<XposedInterface.HookHandle> getOldHookHandles(); }}
 default void onModuleLoaded(ModuleLoadedParam p) {{ {T} }} default void onPackageLoaded(PackageLoadedParam p) {{ {T} }}
 default void onPackageReady(PackageReadyParam p) {{ {T} }} default void onSystemServerStarting(SystemServerStartingParam p) {{ {T} }}
 default boolean onHotReloading(HotReloadingParam p) {{ {T} }} default void onHotReloaded(HotReloadedParam p) {{ {T} }} }}''')
# Minimal concrete inherited API surface, matching the no-arg entry shape, not its implementation.
java('io.github.libxposed.api.XposedModule', f'''import java.lang.reflect.*;
public abstract class XposedModule implements XposedInterface,XposedModuleInterface {{
 public XposedModule() {{ {T} }} public int getApiVersion() {{ {T} }} public String getFrameworkName() {{ {T} }}
 public String getFrameworkVersion() {{ {T} }} public long getFrameworkProperties() {{ {T} }}
 public HookBuilder hook(Executable e) {{ {T} }} public HookBuilder hookClassInitializer(Class<?> c) {{ {T} }}
 public boolean deoptimize(Executable e) {{ {T} }} public Invoker<?,Method> getInvoker(Method m) {{ {T} }}
 public <A> CtorInvoker<A> getInvoker(Constructor<A> c) {{ {T} }} public void log(int p,String t,String m,Throwable e) {{ {T} }}
 public android.content.SharedPreferences getRemotePreferences(String g) {{ {T} }}
 public String[] listRemoteFiles() {{ {T} }} public android.os.ParcelFileDescriptor openRemoteFile(String n) {{ {T} }} }}''')
java('io.github.libxposed.service.HookedTarget', 'public final class HookedTarget {}')
java('io.github.libxposed.service.HotReloadResult', 'public final class HotReloadResult {}')
java('io.github.libxposed.service.XposedService', f'''public final class XposedService {{
 public static final long PROP_CAP_REMOTE=2L; public int getApiVersion() {{ {T} }}
 public String getFrameworkName() {{ {T} }} public String getFrameworkVersion() {{ {T} }} public long getFrameworkProperties() {{ {T} }}
 public java.util.List<String> getScope() {{ {T} }}
 public interface OnScopeEventListener {{ default void onScopeRequestApproved(java.util.List<String> approved) {{ {T} }}
 default void onScopeRequestFailed(String message) {{ {T} }} }}
 public void requestScope(java.util.List<String> packages,OnScopeEventListener listener) {{ {T} }}
 public void removeScope(java.util.List<String> packages) {{ {T} }}
 public interface HotReloadCallback {{ void onHotReloadResult(HookedTarget target,HotReloadResult result); }}
 public java.util.List<HookedTarget> getRunningTargets() {{ {T} }}
 public void hotReloadModule(HookedTarget target,android.os.Bundle extras,HotReloadCallback callback) {{ {T} }}
 public android.content.SharedPreferences getRemotePreferences(String group) {{ {T} }}
 public String[] listRemoteFiles() {{ {T} }} public android.os.ParcelFileDescriptor openRemoteFile(String name) {{ {T} }}
 public boolean deleteRemoteFile(String name) {{ {T} }} public void deleteRemotePreferences(String group) {{ {T} }} }}''')
java('io.github.libxposed.service.XposedServiceHelper', f'''public final class XposedServiceHelper {{
 public interface OnServiceListener {{ void onServiceBind(XposedService service); void onServiceDied(XposedService service); }}
 public static void registerListener(OnServiceListener listener) {{ {T} }} }}''')
files['kava/Resolvers.kt'] = '''package com.highcapable.kavaref.resolver
class MethodResolver<T : Any>(val self: java.lang.reflect.Method)
class ConstructorResolver<T : Any>(val self: java.lang.reflect.Constructor<T>)
'''
files['kava/KavaRef.kt'] = '''package com.highcapable.kavaref
import com.highcapable.kavaref.resolver.MethodResolver
class MethodCondition<T:Any> { var name: String = ""; fun parameters(vararg types: Any) { error("SIGNATURE FIXTURE") } }
class KavaRef {
 companion object { fun <T:Any> Class<T>.resolve() = MemberScope<T>() }
 class MemberScope<T:Any> {
   fun firstMethod(block: MethodCondition<T>.() -> Unit): MethodResolver<T> = error("SIGNATURE FIXTURE")
 }
}
'''

# RoxyHook 0.3.0 used signatures. These remain compile-only; this is not android.jar.
java('android.content.Context', f'''public class Context {{
 public static final int RECEIVER_EXPORTED=2, CONTEXT_IGNORE_SECURITY=2;
 public String getPackageName() {{ {T} }} public Context getApplicationContext() {{ {T} }}
 public Context createPackageContext(String name,int flags) throws android.content.pm.PackageManager.NameNotFoundException {{ {T} }}
 public android.content.res.Resources getResources() {{ {T} }} public android.content.res.Resources.Theme getTheme() {{ {T} }}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter filter) {{ {T} }}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter filter,int flags) {{ {T} }}
 public void unregisterReceiver(BroadcastReceiver r) {{ {T} }} public void sendBroadcast(Intent intent) {{ {T} }}
}}''')
java('android.content.pm.ApplicationInfo', 'public class ApplicationInfo { public String packageName; public String processName; public int uid; }')
java('android.content.pm.ProviderInfo', 'public class ProviderInfo {}')
java('android.content.pm.PackageManager', 'public class PackageManager { public static class NameNotFoundException extends Exception {} }')
java('android.os.Process', f'public class Process {{ public static int myUid() {{ {T} }} }}')
java('android.os.Build', 'public class Build { public static class VERSION { public static int SDK_INT=37; } }')
java('android.os.Handler', f'''public class Handler {{
 public Handler(Looper looper) {{ {T} }} public boolean post(Runnable r) {{ {T} }}
 public boolean postDelayed(Runnable r,long delay) {{ {T} }} public void removeCallbacks(Runnable r) {{ {T} }} }}''')
java('android.content.BroadcastReceiver', 'public abstract class BroadcastReceiver { public abstract void onReceive(Context context,Intent intent); }')
java('android.content.IntentFilter', f'public class IntentFilter {{ public IntentFilter(String action) {{ {T} }} }}')
java('android.content.Intent', f'''public class Intent {{ public Intent(String action) {{ {T} }}
 public String getAction() {{ {T} }} public Intent setPackage(String name) {{ {T} }}
 public Intent putExtra(String key,byte[] value) {{ {T} }} public byte[] getByteArrayExtra(String key) {{ {T} }} }}''')
java('android.content.res.Configuration', 'public class Configuration {}')
java('android.content.res.Resources', f'''public class Resources {{ public static class Theme {{}}
 public int getIdentifier(String name,String type,String pkg) {{ {T} }}
 public String getString(int id,Object...args) {{ {T} }}
 public android.graphics.drawable.Drawable getDrawable(int id,Theme theme) {{ {T} }}
 public java.io.InputStream openRawResource(int id) {{ {T} }} }}''')
java('android.graphics.drawable.Drawable', 'public class Drawable {}')
java('android.app.Application', f'''public class Application extends android.content.Context {{
 final void attach(android.content.Context context) {{ {T} }}
 protected void attachBaseContext(android.content.Context context) {{ {T} }}
 public void onCreate() {{ {T} }} public void onTerminate() {{ {T} }} public void onLowMemory() {{ {T} }}
 public void onTrimMemory(int level) {{ {T} }} public void onConfigurationChanged(android.content.res.Configuration config) {{ {T} }} }}''')
java('android.app.Activity', f'''public class Activity extends android.content.Context {{
 final void attach(android.content.Context context) {{ {T} }}
 protected void onCreate(android.os.Bundle saved) {{ {T} }} protected void onStart() {{ {T} }}
 protected void onResume() {{ {T} }} protected void onPause() {{ {T} }} protected void onStop() {{ {T} }}
 protected void onDestroy() {{ {T} }} protected void onSaveInstanceState(android.os.Bundle saved) {{ {T} }}
 protected void onNewIntent(android.content.Intent intent) {{ {T} }} protected void onActivityResult(int request,int result,android.content.Intent intent) {{ {T} }}
 public void setContentView(android.view.View view) {{ {T} }} }}''')
java('android.os.IBinder', 'public interface IBinder {}')
java('android.app.Service', f'''public class Service extends android.content.Context {{
 public final void attach(android.content.Context context) {{ {T} }}
 public void onCreate() {{ {T} }} public void onDestroy() {{ {T} }}
 public int onStartCommand(android.content.Intent intent,int flags,int id) {{ {T} }}
 public android.os.IBinder onBind(android.content.Intent intent) {{ {T} }}
 public boolean onUnbind(android.content.Intent intent) {{ {T} }} }}''')
java('android.content.ContentProvider', f'''public class ContentProvider {{
 public Context getContext() {{ {T} }} public void attachInfo(Context context,android.content.pm.ProviderInfo info) {{ {T} }}
 public boolean onCreate() {{ {T} }} public void shutdown() {{ {T} }} }}''')
# Add the exact API-102 metadata getters exercised by the generated native entry.
name='io/github/libxposed/api/XposedInterface.java'
files[name]=files[name].replace('long PROP_CAP_REMOTE=2L;', 'android.content.pm.ApplicationInfo getModuleApplicationInfo(); long PROP_CAP_REMOTE=2L;')
name='io/github/libxposed/api/XposedModule.java'
files[name]=files[name].replace('public XposedModule()', 'public android.content.pm.ApplicationInfo getModuleApplicationInfo() { '+T+' } public XposedModule()')
name='io/github/libxposed/api/XposedModuleInterface.java'
files[name]=files[name].replace('interface PackageLoadedParam {', 'interface PackageLoadedParam { android.content.pm.ApplicationInfo getApplicationInfo();')

for name,text in files.items():
    dest=root/name; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_text(text+'\n')
print(f'Generated {len(files)} compile-only fixtures in {root}')
