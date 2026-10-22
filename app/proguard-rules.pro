# Keep names readable in crash logs; shrinking still removes unused code.
-dontobfuscate

# kotlinx.serialization (rules also ship with the library; these are a safety net)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.octopas.sexy.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.octopas.sexy.**$$serializer { *; }

# OkHttp optional platform deps
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
