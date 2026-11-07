# Keep annotations for serialization/reflection
-keepattributes *Annotation*,InnerClasses

# Kotlinx Serialization (avoid stripping generated serializers)
-keepclassmembers class ** {
    ** Companion;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class ** {
    public static kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer { *; }

# Remove noisy debug logs (keep w/e)
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
