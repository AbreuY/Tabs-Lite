package com.gbros.tabslite

import android.app.Activity
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.util.Log
import android.view.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.net.toUri
import androidx.navigation.findNavController
import com.google.android.material.snackbar.Snackbar
import com.gbros.tabslite.adapters.MyTabBasicRecyclerViewAdapter
import com.gbros.tabslite.data.TabBasic
import com.gbros.tabslite.workers.SearchHelper
import com.google.android.gms.instantapps.InstantApps
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.util.*

/**
 * A fragment representing a list of Items.
 * Activities containing this fragment MUST implement the
 * [SongVersionFragment.OnListFragmentInteractionListener] interface.
 */
class SongVersionFragment : Fragment() {

    private var searchHelper: SearchHelper? = null
    private var songVersions : List<TabBasic> = emptyList()
    private var listener: OnListFragmentInteractionListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        searchHelper = (activity as SearchResultsActivity).searchHelper
        arguments?.let { it ->
            // possible tab.type's: "Tab" (not 100% sure on this one), "Chords", "Official"
            // filter out "official" tabs -- the ones without nice chords and a "content" field.
            // also filter out tabs vs chords.  // todo: maybe implement tabs
            songVersions = (it.getParcelableArray(ARG_SONG_VERSIONS) as Array<TabBasic>).filter { tab -> tab.type == "Chords" }
            songVersions = songVersions.sortedWith(compareByDescending {it.votes})  // thanks https://www.programiz.com/kotlin-programming/examples/sort-custom-objects-property
        }

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.list_item_song_version, container, false)
        val rView = view.findViewById<RecyclerView>(R.id.song_version_list)
        // Set the adapter
        if (rView is RecyclerView) {
            with(rView) {
                listener = object: OnListFragmentInteractionListener {
                    override fun onListFragmentInteraction(tabId: Int) {
                        Log.v(javaClass.simpleName, "Navigating to tab detail fragment (tabId: $tabId)")
                        (activity as SearchResultsActivity).getVersions = GlobalScope.async{ searchHelper!!.fetchTab(tabId)}  // async task that gets tab from the internet if it doesn't exist in our db yet


                        // get the tab's URL
/*
                        val artist = tab?.let { it.artistName.trim().toLowerCase(Locale.US).replace(' ', '-').replace("[^\\w\\d-]".toRegex(), "") }
                        val name = tab?.let { it.songName.trim().toLowerCase(Locale.US).replace(' ', '-').replace("[^\\w\\d-]".toRegex(), "") }
                        var url = "tabslite.com/tab/"
                        if(artist != null && name != null && artist.isNotBlank() && name.isNotBlank()) {
                            url += "$artist/$name-"
                        }
                        url += tabId.toString()
*/

                        val tab = songVersions.find { tab -> tab.tabId == tabId }
                        val i = Intent(Intent.ACTION_VIEW)
                        if (tab != null) {
                            i.data = tab.getUrl().toUri()
                            i.setClass(context, Class.forName("com.gbros.tabslite.TabDetailActivity"))
                            startActivity(i)
                        } else {
                            Log.e(javaClass.simpleName, "Could not start TabDetailActivity because tab was null in SongVersionFragment.")
                        }
/*
                        val direction = SongVersionFragmentDirections.actionSongVersionFragmentToTabDetailFragment(tabId)
                        view?.findNavController()?.navigate(direction)
*/
                    }
                }

                layoutManager = LinearLayoutManager(context)
                adapter = MyTabBasicRecyclerViewAdapter(songVersions, listener)
            }
        }

        // set up toolbar and back button
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        (activity as AppCompatActivity).let {
            it.setSupportActionBar(toolbar)
            it.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            it.supportActionBar?.setDisplayShowHomeEnabled(true)
            it.supportActionBar?.setDisplayShowTitleEnabled(true)
            it.supportActionBar?.title = songVersions[0].toString()
        }

        return view
    }


    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        searchHelper = (activity as SearchResultsActivity).searchHelper
        requireActivity().menuInflater.inflate(R.menu.menu_main, menu)

        if(com.google.android.gms.common.wrappers.InstantApps.isInstantApp(context)){
            menu.findItem(R.id.get_app).isVisible = true
        }

        implementSearch(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if(item.itemId == R.id.dark_mode_toggle) {
            context?.let { (activity?.application as DefaultApplication).darkModeDialog(it) }  // show dialog asking user which mode they want
            true
        } else if(item.itemId == R.id.get_app) {
            val postInstall = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .setPackage("com.gbros.tabslite")
            InstantApps.showInstallPrompt((activity as Activity), postInstall, 0, null)

            true
        } else {
            false // let someone else take care of this click
        }
    }

    private fun implementSearch(menu: Menu) {

        if(view == null){
            Log.e(javaClass.simpleName, "Search could not be implemented due to a null view.")
            return
        }

        //setup search
        val searchManager = (activity as AppCompatActivity).getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = menu.findItem(R.id.search).actionView as SearchView
        searchView.setSearchableInfo(searchManager.getSearchableInfo(ComponentName((activity as AppCompatActivity), (activity as AppCompatActivity).javaClass)))

        //set up search suggestions
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                Log.v(javaClass.simpleName, "Query text changed to '$newText' in SongVersionFragment.")
                searchHelper?.updateSuggestions(newText) //update the suggestions
                return false
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                (activity as AppCompatActivity).finish()    // finish this activity
                return false // tell the searchview that we didn't handle the search so it still calls another search
            }

        })
        searchView.suggestionsAdapter = searchHelper?.mAdapter
        val onSuggestionListener = object : SearchView.OnSuggestionListener {
            override fun onSuggestionClick(position: Int): Boolean {
                val cursor: Cursor = searchHelper?.mAdapter?.getItem(position) as Cursor
                val txt: String = cursor.getString(cursor.getColumnIndex("suggestion"))
                searchView.setQuery(txt, true)
                return true
            }

            // todo: what does this mean?
            override fun onSuggestionSelect(position: Int): Boolean {
                // Your code here
                return true
            }
        }
        searchView.setOnSuggestionListener(onSuggestionListener)

/*
        val titleView = view!!.findViewById(R.id.title) as TextView
        // set up search expand hides title
        searchView.setOnSearchClickListener { _ ->
            titleView.isGone = true
        }

        searchView.setOnCloseListener { ->
            titleView.isGone = false

            false
        }
*/

    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson
     * [Communicating with Other Fragments](http://developer.android.com/training/basics/fragments/communicating.html)
     * for more information.
     */
    interface OnListFragmentInteractionListener {
        // TODO: Update argument type and name
        fun onListFragmentInteraction(tabId: Int)
    }

    companion object {

        // TODO: Customize parameter argument names
        const val ARG_SONG_VERSIONS = "songVersions"

        // TODO: Customize parameter initialization
        @JvmStatic
        fun newInstance(songVersions: Array<TabBasic>) =
                SongVersionFragment().apply {
                    arguments = Bundle().apply {
                        putParcelableArray(ARG_SONG_VERSIONS, songVersions)
                    }
                }
    }
}
