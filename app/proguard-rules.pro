# Keep rules for this project.
#
# Important:
# Avoid broad package-level -keep rules here. AndroidX/Compose/ML Kit libraries
# already ship consumer rules, and broad keep rules can block R8 shrinking,
# causing very large APKs.
#
# Add highly-targeted keep rules only when you hit runtime reflection issues.

# If WebView JavaScript interfaces are used, keep only exposed JS methods.
#-keepclassmembers class com.mln.tongji_canvas.** {
#    @android.webkit.JavascriptInterface <methods>;
#}