# kotlinx.serialization keeps its generated serializers via @Serializable, but R8
# needs the companion/serializer members to survive.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher loads its native library by name at runtime.
-keep class net.sqlcipher.** { *; }
-keep interface net.sqlcipher.** { *; }

# Tink reflects over its key protos.
-keep class com.google.crypto.tink.** { *; }
