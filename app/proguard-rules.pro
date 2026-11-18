# ML Kit discovers these registrars through their reflective no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
