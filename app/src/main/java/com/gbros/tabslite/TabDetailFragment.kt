package com.gbros.tabslite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.view.isGone
import androidx.core.widget.NestedScrollView
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.gbros.tabslite.data.TabFull
import com.gbros.tabslite.databinding.FragmentTabDetailBinding
import com.gbros.tabslite.utilities.InjectorUtils
import com.gbros.tabslite.viewmodels.TabDetailViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue


/**
 * A fragment representing a single Tab detail screen
 */
class TabDetailFragment : Fragment() {

    private val args: TabDetailFragmentArgs by navArgs()
    private val timerHandler = Handler()
    private var isScrolling: Boolean = false
    private var scrollDelayMs: Long = 20  // default scroll speed (smaller is faster)

    private lateinit var tab: TabFull
    private lateinit var viewModel: TabDetailViewModel
    private lateinit var binding: FragmentTabDetailBinding
    private lateinit var optionsMenu: Menu
    private var spannableText: SpannableStringBuilder = SpannableStringBuilder()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_tab_detail, container, false)
        if(activity is SearchResultsActivity){
            (activity as SearchResultsActivity).getVersions.invokeOnCompletion(onDataStored())
        } else {
            val getDataJob = GlobalScope.async { (activity as ISearchHelper).searchHelper?.fetchTab(args.tabId) }
            getDataJob.invokeOnCompletion(onDataStored())
        }
        setHasOptionsMenu(true)

        binding.apply {
            lifecycleOwner = viewLifecycleOwner

            // autoscroll
            val timerRunnable: Runnable = object : Runnable {
                override fun run() {
                    // todo: make this time (the 20) adjustable
                    tabDetailScrollview.smoothScrollBy(0, 1) // 5 is how many pixels you want it to scroll vertically by
                    timerHandler.postDelayed(this, scrollDelayMs) // 10 is how many milliseconds you want this thread to run
                }
            }
            callback = object : Callback { override fun scrollButtonClicked() {
                if(isScrolling) {
                    // stop scrolling
                    timerHandler.removeCallbacks(timerRunnable)
                    fab.setImageResource(R.drawable.ic_fab_autoscroll)
                    autoscrollSpeed.isGone = true
                } else {
                    // start scrolling
                    timerHandler.postDelayed(timerRunnable, 0)
                    fab.setImageResource(R.drawable.ic_fab_pause_autoscroll)
                    autoscrollSpeed.isGone = false
                }
                isScrolling = !isScrolling
            } }


            // create toolbar scroll change worker
            var isToolbarShown = false
            val scrollChangeListener = NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->

                // User scrolled past image to height of toolbar and the title text is
                // underneath the toolbar, so the toolbar should be shown.
                val shouldShowToolbar = scrollY > binding.toolbar.height

                // The new state of the toolbar differs from the previous state; update
                // appbar and toolbar attributes.
                if (isToolbarShown != shouldShowToolbar) {
                    isToolbarShown = shouldShowToolbar

                    // Use shadow animator to add elevation if toolbar is shown
                    binding.appbar.isActivated = shouldShowToolbar

                    // Show the plant name if toolbar is shown
                    // hacking this using the Activity title.  It seems to show whenever title isn't enabled
                    // and our normal title won't show so I'm just using reverse psychology here
                    binding.toolbarLayout.isTitleEnabled = !shouldShowToolbar
                }
            }
            // scroll change listener begins at Y = 0 when image is fully collapsed
            tabDetailScrollview.setOnScrollChangeListener(scrollChangeListener)

            // title bar
            (activity as AppCompatActivity).apply {
                setSupportActionBar(binding.toolbar)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setDisplayShowHomeEnabled(true)
                supportActionBar?.setDisplayShowTitleEnabled(true)
            }

            // transpose
            transposeUp.setOnClickListener{_ -> transpose(true)}
            transposeDown.setOnClickListener{_ -> transpose(false)}

            // autoscroll speed seek bar
            binding.autoscrollSpeed.clipToOutline = true  // not really needed since the background is enough bigger
            binding.autoscrollSpeed.setOnSeekBarChangeListener(seekBarChangeListener)
            binding.autoscrollSpeed.isGone = true
        }

            return binding.root
    }

    private var seekBarChangeListener: OnSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            // updated continuously as the user slides the thumb
            //convert progress to delay between 1px updates
            var myDelay = (100 - progress) / 100.0  // delay on a scale of 0 to 1
            myDelay *= 34                           // delay on a scale of 0 to 34
            myDelay += 2                            // delay on a scale of 2 to 36
            scrollDelayMs = (myDelay).toLong()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            // called when the user first touches the SeekBar
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            // called after the user finishes moving the SeekBar
        }
    }

    private fun chordClicked(chordName: String){
        val api = (activity as ISearchHelper).searchHelper?.api ?: return  // return if api null
        val input = ArrayList<String>()
        input.add(chordName)

        // update the database from the web, then get chords from the database and add them to the chordPageAdapter
        val updateJob = GlobalScope.async { api.updateChordVariations(input) }
        updateJob.invokeOnCompletion { cause ->
            if(cause != null){
                Log.w(javaClass.simpleName, "Chord update didn't work.")
            }
            val getChordsJob = GlobalScope.async { api.getChordVariations(chordName) }
            getChordsJob.invokeOnCompletion { cause ->
                if (cause != null) {
                    Log.w(javaClass.simpleName, "Getting chords from db didn't work.")
                    Unit
                } else {
                    val chordVars = getChordsJob.getCompleted()
                    (activity as AppCompatActivity).runOnUiThread {
                        ChordBottomSheetDialogFragment.newInstance(chordVars).show(
                                (activity as AppCompatActivity).supportFragmentManager, null)
                    }
                    Unit
                }
            }
            Unit
        }
    }

    private fun transpose(howMuch: Int){
        val numSteps = howMuch.absoluteValue
        val currentSpans = spannableText.getSpans(0,spannableText.length, ClickableSpan::class.java)

        for (span in currentSpans) {
            val startIndex = spannableText.getSpanStart(span)
            val endIndex = spannableText.getSpanEnd(span)
            val currentText = span.toString()
            spannableText.removeSpan(span)

            var newText = currentText
            if (howMuch > 0) {
                // transpose up
                for(i in 0 until numSteps){
                    newText = transposeUp(newText)
                }
            } else {
                // transpose down
                for(i in 0 until numSteps){
                    newText = transposeDown(newText)
                }
            }


            spannableText.replace(startIndex,endIndex, newText)  // edit the text
            spannableText.setSpan(makeSpan(newText), startIndex, startIndex+newText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)  // add a new span
        }

        binding.tabContent.setTabContent(spannableText)
        binding.transposeAmt.text = tab.transposed.toString()
        GlobalScope.launch{(activity as ISearchHelper).searchHelper?.updateTabTransposeLevel(tab.tabId, tab.transposed)}
    }

    private fun transpose(up: Boolean){
        val howMuch = if(up) 1 else -1
        tab.transposed += howMuch

        //13 half steps in an octave (both sides inclusive)
        if(tab.transposed >= 12) {
            tab.transposed -= 12
        } else if (tab.transposed <= -12) {
            tab.transposed += 12
        }

        transpose(howMuch)
    }
    private fun transposeUp(text: String): String {
        return when {
            text.startsWith("A#", true) -> "B" + text.substring(2)
            text.startsWith("Ab", true) -> "A" + text.substring(2)
            text.startsWith("A", true) -> "A#" + text.substring(1)
            text.startsWith("Bb", true) -> "B" + text.substring(2)
            text.startsWith("B", true) -> "C" + text.substring(1)
            text.startsWith("C#", true) -> "D" + text.substring(2)
            text.startsWith("C", true) -> "C#" + text.substring(1)
            text.startsWith("D#", true) -> "E" + text.substring(2)
            text.startsWith("Db", true) -> "D" + text.substring(2)
            text.startsWith("D", true) -> "D#" + text.substring(1)
            text.startsWith("Eb", true) -> "E" + text.substring(2)
            text.startsWith("E", true) -> "F" + text.substring(1)
            text.startsWith("F#", true) -> "G" + text.substring(2)
            text.startsWith("F", true) -> "F#" + text.substring(1)
            text.startsWith("G#", true) -> "A" + text.substring(2)
            text.startsWith("Gb", true) -> "G" + text.substring(2)
            text.startsWith("G", true) -> "G#" + text.substring(1)
            else -> {
                Log.e(javaClass.simpleName, "Weird Chord not transposed: $text")
                text
            }
        }
    }
    private fun transposeDown(text: String): String {
        return when {
            text.startsWith("A#", true) -> "A" + text.substring(2)
            text.startsWith("Ab", true) -> "G" + text.substring(2)
            text.startsWith("A", true) -> "G#" + text.substring(1)
            text.startsWith("Bb", true) -> "A" + text.substring(2)
            text.startsWith("B", true) -> "A#" + text.substring(1)
            text.startsWith("C#", true) -> "C" + text.substring(2)
            text.startsWith("C", true) -> "B" + text.substring(1)
            text.startsWith("D#", true) -> "D" + text.substring(2)
            text.startsWith("Db", true) -> "C" + text.substring(2)
            text.startsWith("D", true) -> "C#" + text.substring(1)
            text.startsWith("Eb", true) -> "D" + text.substring(2)
            text.startsWith("E", true) -> "D#" + text.substring(1)
            text.startsWith("F#", true) -> "F" + text.substring(2)
            text.startsWith("F", true) -> "E" + text.substring(1)
            text.startsWith("G#", true) -> "G" + text.substring(2)
            text.startsWith("Gb", true) -> "F" + text.substring(2)
            text.startsWith("G", true) -> "F#" + text.substring(1)
            else -> {
                Log.e(javaClass.simpleName, "Weird Chord not transposed: $text")
                text
            }
        }
    }

    private fun onDataStored() = { cause: Throwable? ->
        if(cause != null) {
            //oh no; something happened and it failed.  whoops.
            Log.e(javaClass.simpleName, "Error fetching and storing tab data from online source on the async thread.")
            Unit
        }
        startGetData()
        Unit
    }

    //starts here coming from the favorite tabs page; assumes data is already in db
    private fun startGetData() {
        val tabDetailViewModel: TabDetailViewModel by viewModels {
            InjectorUtils.provideTabDetailViewModelFactory(requireActivity(), args.tabId)
        }
        viewModel = tabDetailViewModel
        viewModel.tab.start()
        viewModel.tab.invokeOnCompletion(onDataReceived())
    }

    // app will currently crash if the database actually doesn't have the data (tab = null).  Shouldn't happen irl, but happened in development
    private fun onDataReceived() =  { cause: Throwable? ->
        if(cause != null) {
            //oh no; something happened and it failed.  whoops.
            Log.e(javaClass.simpleName, "Error fetching tab data from database.")
            Unit
        } else {

            tab = viewModel.tab.getCompleted()  // actually get the data

            spannableText = processTabContent(tab.content)
            tab.content = spannableText.toString()

            activity?.runOnUiThread {
                binding.tab = tab  // set view data
                setHeartInitialState()  // set initial state of "save" heart
                (activity as AppCompatActivity).title = tab.toString()  // toolbar title

                binding.progressBar2.isGone = true
                binding.transposeAmt.text = tab.transposed.toString()
                transpose(tab.transposed)  // calls binding.tabContent.setTabContent(spannableText)
            }

            Unit
        }
    }

    private fun setHeartInitialState(){
        if(this::tab.isInitialized && tab.favorite && this::optionsMenu.isInitialized) {
            val heart = optionsMenu.findItem(R.id.action_favorite)
            heart.isChecked = true
            heart.setIcon(R.drawable.ic_favorite)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_tab_detail, menu)
        optionsMenu = menu
        setHeartInitialState()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // share menu item and favorite menu item
        return when (item.itemId) {
            R.id.action_share -> {
                createShareIntent()
                true
            }
            R.id.action_favorite -> {
                if(this::tab.isInitialized) {
                    item.isChecked = !item.isChecked
                    if (item.isChecked) {
                        // now it's a favorite
                        item.setIcon(R.drawable.ic_favorite)
                        tab.favorite = true
                    } else {
                        item.setIcon(R.drawable.ic_unfavorite)
                        tab.favorite = false
                    }
                    viewModel.setFavorite(item.isChecked)
                }
                true
            }
            R.id.action_reload -> {  // reload button clicked
                binding.progressBar2.isGone = false
                val searchJob = GlobalScope.async {
                    (activity as ISearchHelper).searchHelper?.fetchTab(tabId = args.tabId, force = true)
                }
                searchJob.start()
                searchJob.invokeOnCompletion(onDataStored())
                true
            }
            else -> false
        }
    }

    // Helper function for calling a share functionality.
    private fun createShareIntent() {
        val shareText = tab.let { tab ->
            if (!this::tab.isInitialized) {
                ""
            } else {
                getString(R.string.share_text_plant, tab.toString(), tab.urlWeb)
            }
        }

        val shareIntent = ShareCompat.IntentBuilder.from(requireActivity())
                .setText(shareText)
                .setType("text/plain")
                .createChooserIntent()
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(shareIntent)
    }

    interface Callback {
        fun scrollButtonClicked()
    }

    private fun processTabContent(text: CharSequence): SpannableStringBuilder{
        var text = text.replace("\\[tab]".toRegex(), "\n").replace("\\[/tab]".toRegex(), "")

        var lastIndex = 0
        val chords = ArrayList<Pair<Int, Int>>()
        while(true) {
            val startIndex = text.indexOf("[ch]", lastIndex)
            if (startIndex < 0) {
                break
            }
            text = text.removeRange(startIndex..startIndex + 3)

            val endIndex = text.indexOf("[/ch]", startIndex)
            if (endIndex < 0) {
                break
            }  // this check shouldn't be needed
            text = text.removeRange(endIndex..endIndex + 4)
            lastIndex = endIndex

            chords.add(startIndex to endIndex)
        }


        val spannableString = SpannableStringBuilder(text)
        for(chord in chords){
            val chordName = text.substring(chord.first until chord.second)
            val clickableSpan = makeSpan(chordName)
            spannableString.setSpan(clickableSpan, chord.first, chord.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return spannableString
    }
    private fun TextView.setTabContent(spannableString: SpannableStringBuilder) {
        this.movementMethod = LinkMovementMethod.getInstance() // without LinkMovementMethod, link can not click
        this.setText(spannableString, TextView.BufferType.SPANNABLE)
    }

    //thanks https://stackoverflow.com/a/51561533/3437608
    fun Context.getColorFromAttr( @AttrRes attrColor: Int,
            typedValue: TypedValue = TypedValue(),
            resolveRefs: Boolean = true
    ): Int {
        theme.resolveAttribute(attrColor, typedValue, resolveRefs)
        return typedValue.data
    }

    private fun makeSpan(chordName: String): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(view: View) {
                Selection.setSelection((view as TextView).text as Spannable, 0)
                view.invalidate()

                chordClicked(chordName)
                Snackbar.make(view, "Loading chord $chordName...", Snackbar.LENGTH_SHORT).show() // todo: eventually implement chord-specific functionality here

            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                if(context != null) {
                    ds.color = context!!.getColorFromAttr(R.attr.colorOnSecondary)
                    ds.bgColor = context!!.getColorFromAttr(R.attr.colorPrimarySurface)
                }
            }

            override fun toString(): String {
                return chordName
            }
        }
    }
}
