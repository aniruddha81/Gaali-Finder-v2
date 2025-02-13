package com.aniruddha81.gaalifinderv2.appwrite

import android.content.Context
import io.appwrite.Client
import io.appwrite.services.Storage


object AppwriteClient {
    private lateinit var storage: Storage

    fun init(context: Context) {
        val client = Client(context)
            .setEndpoint("https://cloud.appwrite.io/v1")
            .setProject("YOUR_PROJECT_ID")

        storage = Storage(client)
    }

    fun getStorage(): Storage {
        return storage
    }
}
