@echo off
set "ANDROID_SDK_ROOT=D:\LacViet\Android\sdk"
C:\Users\PHONGQK\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat testDebugUnitTest --console=plain --stacktrace > D:\LacViet\LingoPlay\android\build-test.log 2>&1
exit /b %errorlevel%
