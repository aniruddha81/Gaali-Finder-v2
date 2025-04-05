# 🎵 GaaliFinderV2  

GaaliFinderV2 is an **audio clip sharing app** for meme lovers to share viral meme audio clips with friends via social media. The app includes a collection of trending audio clips from viral memes, and users can also add their own clips locally.  

## 🚀 Features  
- 🎶 **Trending Meme Audio Clips** – Access viral meme sounds.  
- 📂 **Local Audio Import** – Add personal audio clips stored locally.  
- 🔄 **Automatic Updates** – New clips uploaded by the admin are automatically synced.  
- 📤 **Easy Sharing** – Share clips via social media apps.  
- 🔍 **Search & Organize** – Find clips quickly with search functionality.  
- 🗑️ **Manage Files** – Delete unwanted clips from the app.  
- ✏️ **Rename Files** – Rename audio files by double-clicking.
- 🔔 **Notification Updates** – Get notifications when new files are added.

## 📌 Setup Instructions  

### 1️⃣ Clone the Repository  
```sh
git clone https://github.com/aniruddha81/Gaali-Finder-v2-.git
cd GaaliFinderV2
```

### 2️⃣ Configure Appwrite Credentials
Set your Appwrite Project ID and Bucket ID in `app/src/main/java/com/aniruddha81/gaalifinderv2/Constants.kt`:
```kotlin
object Constants {
    const val APPWRITE_PROJECT_ID = "your_project_id"
    const val APPWRITE_BUCKET_ID = "your_bucket_id"
}
```
### 3️⃣ Add `google-services.json` File
Download the `google-services.json` file from your Firebase project and place it in the `app` directory of this project.

### 4️⃣ Build & Run the App
Open the project in Android Studio, connect your device/emulator, and run the app. The apk file is in the release option.

## 📌 How It Works
- **Admin-Only Uploads** – Only the admin can upload new clips to the remote server.
- **Local Audio Storage** – Users can add custom clips, but they remain on their device.
- **Auto-Sync** – When the admin uploads new clips, they automatically sync to users' apps.
