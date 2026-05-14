# 隐形相机 (StealthCam)

一个将相机预览嵌入网页角落的 Android 应用。

## 功能
- 📷 **音量键短按** → 拍照（有闪光反馈）
- 🎬 **音量键长按 0.8秒** → 开始录像
- ⏹ **录像中按音量键** → 停止录像
- 🌐 **内置浏览器** → 默认打开百度（可修改）
- 📁 **保存路径** → DCIM/隐形相机

## 快速获取 APK（无需本地环境）

### 方法一：GitHub Actions 自动构建（推荐）

1. 注册 [GitHub](https://github.com) 账号（免费）
2. 新建仓库，上传本项目所有文件
3. 点击仓库页面的 **Actions** 标签
4. 点击 **Build APK** → **Run workflow**
5. 等待 3-5 分钟，下载 `StealthCam-debug.apk`

### 方法二：Gitpod 在线构建

1. 把代码上传到 GitHub
2. 访问 `https://gitpod.io/#你的GitHub仓库地址`
3. 在终端运行：
   ```bash
   chmod +x gradlew
   ./gradlew assembleDebug
   ```
4. 下载 `app/build/outputs/apk/debug/app-debug.apk`

### 方法三：本地构建（安装 Android Studio）

1. 下载 [Android Studio](https://developer.android.com/studio)
2. 打开本项目
3. Build → Build Bundle(s)/APK(s) → Build APK(s)

## 安装 APK 到手机

1. 手机设置 → 安全 → 开启"允许未知来源"
2. 将 APK 文件传到手机
3. 点击安装

## 自定义默认网页

修改 `MainActivity.kt` 第 68 行：
```kotlin
loadUrl("https://www.baidu.com")  // 改为你想要的网址
```
