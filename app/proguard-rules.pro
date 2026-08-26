# kotlinx.serialization keeps its generated serializers via @Serializable, but R8
# needs the companion/serializer members to survive.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher's own keep rules ship inside the AAR (proguard.txt, covering
# net.zetetic.** native methods), so nothing is needed here.
#
# There used to be a rule naming net.sqlcipher.**, which is the DEPRECATED
# predecessor artifact. It matched nothing, kept nothing, and looked like
# protection. If the library ever drops its consumer rules, the package to keep
# is net.zetetic.database.sqlcipher.**, not net.sqlcipher.**.

# Tink reflects over its key protos.
-keep class com.google.crypto.tink.** { *; }
