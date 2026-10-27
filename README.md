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

### 2️⃣ Point the app at the API
Add these to `local.properties` (git-ignored):
```properties
API_BASE_URL=https://api.example.com
GOOGLE_WEB_CLIENT_ID=your_google_oauth_web_client_id
```
The API itself lives in [`server/`](server/) (Go + SQLite) and is deployed with [`infra/`](infra/).
See [`DEPLOYMENT.md`](DEPLOYMENT.md) for how it is hosted.
### 3️⃣ Add `google-services.json` File
Download the `google-services.json` file from your Firebase project and place it in the `app` directory of this project.

### 4️⃣ Build & Run the App
Open the project in Android Studio, connect your device/emulator, and run the app. The apk file is in the release option.

## 📌 How It Works
- **Shared Library** – Signed-in users (Google, or email and password with a verified address) upload clips to the shared library. Each file may be up to 200 KB; free accounts get 10 MB in total.
- **Local Cache** – Clips are downloaded on first play and kept on the device so they work offline.
- **Auto-Sync** – New clips from anyone appear in everyone's app on the next sync.
