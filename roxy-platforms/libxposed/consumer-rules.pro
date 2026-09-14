# Entry names are literal in META-INF/xposed/java_init.list: keep them stable.
-keep public class * extends hk.uwu.roxyhook.platform.libxposed.RoxyXposedModule {
    public <init>();
}
-dontwarn io.github.libxposed.annotation.**
