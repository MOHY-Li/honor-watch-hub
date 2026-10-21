import io
p = "android/app/src/main/java/dev/watchhub/app/MainActivity.kt"
s = io.open(p, encoding="utf-8").read()
old = "postDelayed({"
s = s.replace(old, "postDelayed(Runnable {")
s = s.replace("                            }\n                            is Updater.InstallResult.Failed", "                            }, 1500)\n                            is Updater.InstallResult.Failed")
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("done")
