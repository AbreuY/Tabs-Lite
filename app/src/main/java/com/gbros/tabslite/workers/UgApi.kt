package com.gbros.tabslite.workers

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.gbros.tabslite.data.*
import com.gbros.tabslite.utilities.ApiHelper
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.IllegalStateException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class UgApi(
        val context: Context
) {
    private val gson = Gson()

    private var lastSuggestionRequest = ""
    private var lastResult = SearchSuggestionType(emptyList())
    suspend fun searchSuggest(q: String): List<String> = coroutineScope {

        var result = lastResult.suggestions
        var query = q
        try {
            if (query.length > 5) { // ug api only allows a max of 5 chars for search suggestion requests.  rest of processing is done in app
                query = query.slice(0 until 5)
            }

            if (query == lastSuggestionRequest) {
                //processing past 5 chars is done in app
                result = lastResult.suggestions.filter { s -> s.contains(q) }
            } else {

                val connection = URL("https://api.ultimate-guitar.com/api/v1/tab/suggestion?q=$query").openConnection() as HttpURLConnection
                val inputStream = connection.inputStream
                val jsonReader = JsonReader(inputStream.reader())
                val searchSuggestionTypeToken = object : TypeToken<SearchSuggestionType>() {}.type
                lastResult = gson.fromJson(jsonReader, searchSuggestionTypeToken)
                result = lastResult.suggestions
                inputStream.close()
            }
        } catch (ex: FileNotFoundException) {
            // no suggestions for this query
            Log.i(javaClass.simpleName, "Search suggestions file not found for query $query.", ex)
            this.cancel("SearchSuggest coroutine canceled due to 404", ex)
        } catch (ex: Exception) {
            Log.e(javaClass.simpleName, "SearchSuggest error while finding search suggestions.")
            this.cancel("SearchSuggest coroutine canceled due to unexpected error", ex)
        }


        // suggestion caching
        lastSuggestionRequest = query

        // processing past 5 chars is done in app
        result
    }

    suspend fun search(q: String, pageNum: Int = 1): SearchRequestType = coroutineScope {
        val url = "https://api.ultimate-guitar.com/api/v1/tab/search?title=$q&page=$pageNum&type[]=300&official[]=0"
        val inputStream = authenticatedStream(url)
        if (inputStream != null) {
            val jsonReader = JsonReader(inputStream.reader())
            var result = SearchRequestType()

            try {
                val searchResultTypeToken = object : TypeToken<SearchRequestType>() {}.type
                result = gson.fromJson(jsonReader, searchResultTypeToken)
            } catch (ex: JsonSyntaxException) {
                Log.v(javaClass.simpleName, "Search exception.  Probably just a 'did you mean' search suggestion at the end.")
                try {
                    val stringTypeToken = object : TypeToken<String>() {}.type
                    val suggestedSearch: String = gson.fromJson(jsonReader, stringTypeToken)

                    this.cancel("search:$suggestedSearch")
                } catch (ex: IllegalStateException) {
                    inputStream.close()
                    Log.e(javaClass.simpleName, "Search illegal state exception!  Check SearchRequestType for consistency with data.  Url: $url", ex)
                    this.cancel("Illegal State.", ex)
                    throw ex
                }
            }
            inputStream.close()

            Log.v(javaClass.simpleName, "Search for $q page $pageNum success.")
            result
        } else {
            Log.i(javaClass.simpleName, "Error getting search results.  Probably just the end of results for query $q")
            cancel("Error getting search results.  Probably just the end of results for query $q")
            SearchRequestType()
        }
    }

    suspend fun updateChordVariations(chordIds: List<String>, force: Boolean = false, tuning: String = "E A D G B E",
                                   instrument: String = "guitar") = coroutineScope {
        val database = AppDatabase.getInstance(context).chordVariationDao()
        var chordParam = ""
        for (chord in chordIds) {
            if (force || !database.chordExists(chord)){ // if the chord already exists in the db at all, we can assume we have all variations of it.  Not often a new chord is created
                val uChord = URLEncoder.encode(chord, "utf-8")
                chordParam += "&chords[]=$uChord"
            }
        }

        if (chordParam.isNotEmpty()) {
            val uTuning = URLEncoder.encode(tuning, "utf-8")
            val uInstrument = URLEncoder.encode(instrument, "utf-8")
            val url = "https://api.ultimate-guitar.com/api/v1/tab/applicature?instrument=$uInstrument&tuning=$uTuning$chordParam"
            val inputStream = authenticatedStream(url)
            if (inputStream != null) {
                val jsonReader = JsonReader(inputStream.reader())
                val chordRequestTypeToken = object : TypeToken<List<TabRequestType.ChordInfo>>() {}.type
                val results: List<TabRequestType.ChordInfo> = gson.fromJson(jsonReader, chordRequestTypeToken)
                for (result in results) {
                    database.insertAll(result.getChordVariations())
                }
                inputStream.close()
            } else {
                cancel("Error fetching chord(s).")
            }
        }
    }

    suspend fun getChordVariations(chordId: String): List<ChordVariation> = coroutineScope {
        val database = AppDatabase.getInstance(context).chordVariationDao()
        database.getChordVariations(chordId)
    }

    suspend fun getTopTabs(): List<SearchRequestType.Tab> = coroutineScope {
        // 'type[]=300' means just chords (all instruments? use 300, 400, 700, and 800)
        // 'order=hits_daily' means get top tabs today not overall.  For overall use 'hits'
        val inputStream = authenticatedStream("https://api.ultimate-guitar.com/api/v1/tab/explore?date=0&genre=0&level=0&order=hits_daily&page=1&type=0&official=0")
        if (inputStream != null) {
            val jsonReader = JsonReader(inputStream.reader())
            val typeToken = object : TypeToken<List<SearchRequestType.Tab>>() {}.type
            val result: List<SearchRequestType.Tab> = gson.fromJson(jsonReader, typeToken)
            inputStream.close()

            result
        } else {
            Log.e(javaClass.simpleName, "Error fetching top tabs.  AuthenticatedStream returned null")
            this.cancel("Error fetching top tabs.  AuthenticatedStream returned null")
            emptyList()
        }
    }

    suspend fun authenticatedStream(url: String): InputStream? = coroutineScope {
        while(ApiHelper.updatingApiKey){
            delay(10)
        }

        var apiKey = ApiHelper.apiKey
        val deviceId = ApiHelper.getDeviceId()

        try {
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept-Charset", "utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "UGT_ANDROID/5.10.12 (")  // actual value UGT_ANDROID/5.10.11 (ONEPLUS A3000; Android 10)
            conn.setRequestProperty("x-ug-client-id", deviceId)             // stays constant over time; api key and client id are related to each other.
            conn.setRequestProperty("x-ug-api-key", apiKey)                 // updates periodically.

            // handle when the api key is outdated
            if (conn.responseCode == 498) {
                conn.disconnect()
                ApiHelper.updateApiKey()
                apiKey = ApiHelper.apiKey
                conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "UGT_ANDROID/5.10.12 (")  // actual value UGT_ANDROID/5.10.11 (ONEPLUS A3000; Android 10)
                conn.setRequestProperty("x-ug-client-id", deviceId)                   // stays constant over time; api key and client id are related to each other.
                conn.setRequestProperty("x-ug-api-key", apiKey)     // updates periodically.
            }

            Log.v(javaClass.simpleName, "Success fetching url $url")
            conn.getInputStream()
        } catch (ex: FileNotFoundException) {
            Log.i(javaClass.simpleName, "404 NOT FOUND during fetch of url $url with parameters apiKey: $apiKey and deviceId: $deviceId.")
            cancel("Not Found", ex)
            null
        } catch (ex: Exception) {
            Log.e(javaClass.simpleName, "Exception during fetch of url $url with parameters apiKey: $apiKey and deviceId: $deviceId.", ex)
            cancel("Not Found", ex)
            null
        }
    }
}