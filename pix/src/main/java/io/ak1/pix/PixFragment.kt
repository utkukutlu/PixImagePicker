package io.ak1.pix

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toFile
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.ak1.pix.adapters.InstantImageAdapter
import io.ak1.pix.adapters.MainImageAdapter
import io.ak1.pix.databinding.FragmentPixCameraBinding
import io.ak1.pix.helpers.*
import io.ak1.pix.interfaces.OnSelectionListener
import io.ak1.pix.models.Img
import io.ak1.pix.models.Options
import io.ak1.pix.models.PixViewModel
import io.ak1.pix.utility.ARG_PARAM_PIX
import io.ak1.pix.utility.CustomItemTouchListener
import kotlinx.coroutines.*
import java.lang.Runnable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Created By Akshay Sharma on 17,June,2021
 * https://ak1.io
 */

class PixFragment(private val resultCallback: ((PixEventCallback.Results) -> Unit)? = null) :
    Fragment(), View.OnTouchListener {

    private val model: PixViewModel by viewModels()
    private var _binding: FragmentPixCameraBinding? = null
    private val binding get() = _binding!!

    private var permReqLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all {
                    it.value
                }) {
                Log.e("all", "permissions granted calling init again")
                initialise(requireActivity())
            }
        }

    internal val mScrollbarHider = Runnable { binding.hideScrollbar() }
    private var cameraXManager: CameraXManager? = null
    private lateinit var options: Options
    private var mBottomSheetBehavior: BottomSheetBehavior<View>? = null
    private var scope = CoroutineScope(Dispatchers.IO)
    private var colorPrimaryDark = 0

    override fun onResume() {
        super.onResume()
        Log.e("lifecycle", "on resume ${mBottomSheetBehavior?.state}")
        if (mBottomSheetBehavior?.state == BottomSheetBehavior.STATE_COLLAPSED) {
            Handler(Looper.getMainLooper()).postDelayed({
                requireActivity().hideStatusBar()
            }, 200)

        }
    }

    override fun onPause() {
        super.onPause()
        if (mBottomSheetBehavior?.state != BottomSheetBehavior.STATE_COLLAPSED) {
            mBottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("lifecycle", "onCreate")
        options = arguments?.getParcelable(ARG_PARAM_PIX) ?: Options()
        requireActivity().let {
            it.setupScreen()
            it.actionBar?.hide()
            colorPrimaryDark = it.color(R.color.colorPrimaryPix)
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = run {
        Log.e("lifecycle", "onCreateView")
        _binding = FragmentPixCameraBinding.inflate(inflater, container, false)
        binding.root
    }


    @InternalCoroutinesApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.e("lifecycle", "onViewCreated")
        reSetup()
        //in case of resetting the options in an live fragment
        setFragmentResultListener("PixKey") { key, bundle ->
            Log.e("key ", "-> $key")
            options = bundle.getParcelable(ARG_PARAM_PIX) ?: options
            reSetup()
        }
    }

    private fun reSetup() {
        requireActivity().let {
            it.setUpMargins(binding)
            permReqLauncher.permissionsFilter(it, options) {
                Log.e("permissionsFilter", "success")
                initialise(it)
            }
        }

    }


    private fun initialise(context: FragmentActivity) {
        Log.e("initialise", "started")
        cameraXManager = CameraXManager(binding.viewFinder, context, options).also {
            it.startCamera()
        }
        setupAdapters(context)
        setupFastScroller(context)
        observeSelectionList()
        retrieveMedia()
        setBottomSheetBehavior()
        setupControls()
        backPressController()

    }

    override fun onDestroyView() {
        super.onDestroyView()
        model.imageList.removeObservers(requireActivity())
        model.selectionList.removeObservers(requireActivity())
        model.longSelection.removeObservers(requireActivity())
        model.callResults.removeObservers(requireActivity())
    }

    private fun observeSelectionList() {
        model.setOptions(options)
        model.imageList.observe(requireActivity()) {
            instantImageAdapter.addImageList(it.list)
            mainImageAdapter.addImageList(it.list)
            model.selectionList.value?.addAll(it.selection)
            model.selectionList.postValue(model.selectionList.value)
        }
        model.selectionList.observe(requireActivity()) {
            Log.e("selectionList ", "size is now ${it.size}")
            if (it.size == 0) {
                model.longSelection.postValue(false)
            } else if (!model.longSelectionValue) {
                model.longSelection.postValue(true)
            }
            binding.setSelectionText(requireActivity(), it.size)
        }
        model.longSelection.observe(requireActivity()) {
            Log.e("longSelection ", "is now changed to  $it")
            binding.longSelectionStatus(it)
            if (mBottomSheetBehavior?.state ?: BottomSheetBehavior.STATE_COLLAPSED == BottomSheetBehavior.STATE_COLLAPSED) {
                binding.gridLayout.sendButtonStateAnimation(it)
            }
        }
        model.callResults.observe(requireActivity()) { event ->
            event?.getContentIfNotHandledOrReturnNull()?.let { set ->
                binding.gridLayout.sendButtonStateAnimation(show = false, withAnim = false)
                model.selectionList.postValue(HashSet())
                val results = set.map { it.contentUrl }
                resultCallback?.invoke(PixEventCallback.Results(results))
                Log.e("PixEventCallback", "return SUCCESS ${model.selectionListSize}")
                PixBus.returnObjects(
                    event = PixEventCallback.Results(
                        results,
                        PixEventCallback.Status.SUCCESS
                    )
                )

            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFastScroller(context: FragmentActivity) {
        toolbarHeight = context.toPx(56f)
        binding.gridLayout.apply {
            fastscrollScrollbar.hide()
            fastscrollBubble.hide()
            fastscrollHandle.setOnTouchListener(this@PixFragment)
        }
    }


    private fun backPressController() {
        CoroutineScope(Dispatchers.Main).launch {
            PixBus.on(this) {
                Log.e("onBackPressedEvent", "onBackPressedEvent handled")
                val list = model.selectionList.value ?: HashSet()
                when {
                    list.size > 0 -> {
                        for (img in list) {
                            //  options.preSelectedUrls = ArrayList()
                            instantImageAdapter.select(false, img.position)
                            mainImageAdapter.select(false, img.position)
                        }
                        model.selectionList.postValue(HashSet())
                    }
                    mBottomSheetBehavior?.state == BottomSheetBehavior.STATE_EXPANDED -> {
                        mBottomSheetBehavior?.setState(BottomSheetBehavior.STATE_COLLAPSED)
                    }
                    else -> {
                        model.returnObjects()
                    }
                }
            }
        }
    }

    private fun setupControls() {
        binding.setupClickControls(model, cameraXManager, options) { int, uri ->
            when (int) {
                0 -> model.returnObjects()
                1 -> mBottomSheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                2 -> model.longSelection.postValue(true)
                3 -> requireActivity().scanPhoto(uri.toFile()) { it ->
                    if (model.selectionList.value.isNullOrEmpty()) {
                        model.selectionList.value?.add(Img(contentUrl = it))
                        scope.cancel(CancellationException("canceled intentionally"))
                        model.returnObjects()
                        return@scanPhoto
                    }
                    model.selectionList.value?.add(Img(contentUrl = it))
                    Handler(Looper.getMainLooper()).post {
                        binding.setSelectionText(
                            requireActivity(),
                            (model.selectionList.value ?: HashSet()).size
                        )
                        options.preSelectedUrls.clear()
                        options.preSelectedUrls.addAll(
                            (model.selectionList.value ?: HashSet()).map { it.contentUrl })
                        retrieveMedia()
                    }
                }
                4 -> if (model.longSelectionValue) binding.gridLayout.sendButtonStateAnimation(false)
                5 -> if (model.longSelectionValue) binding.gridLayout.sendButtonStateAnimation(true)

            }
        }
    }

    private fun retrieveMedia() {
        Log.e("data retrieval", "started")
        // options.preSelectedUrls.addAll(selectionList)
        if (options.preSelectedUrls.size > options.count) {
            val large = options.preSelectedUrls.size - 1
            val small = options.count
            for (i in large downTo small) {
                options.preSelectedUrls.removeAt(i)
            }
        }
        scope.launch {
            val localResourceManager = LocalResourceManager(requireContext()).apply {
                this.preSelectedUrls = options.preSelectedUrls
            }
            instantImageAdapter.clearList()
            mainImageAdapter.clearList()
            model.retrieveImages(localResourceManager)
        }

    }

    private fun setupAdapters(context: FragmentActivity) {
        val onSelectionListener: OnSelectionListener = object : OnSelectionListener {
            override fun onClick(element: Img?, view: View?, position: Int) {
                Log.e("clicked", "position $position")
                model.onImageSelected(element, position) {
                    val size = model.selectionListSize
                    if (options.count <= size) {
                        requireActivity().toast(size)
                        return@onImageSelected false
                    }
                    position.selection(it)
                    return@onImageSelected true
                }
            }

            override fun onLongClick(element: Img?, view: View?, position: Int) =
                model.onImageLongSelected(element, position) {
                    Log.e("clicked", " long position $position")
                    val size = model.selectionListSize
                    if (options.count <= size) {
                        requireActivity().toast(size)
                        return@onImageLongSelected false
                    }
                    position.selection(it)
                    return@onImageLongSelected true
                }
        }
        instantImageAdapter = InstantImageAdapter(context).apply {
            addOnSelectionListener(onSelectionListener)
        }
        mainImageAdapter = MainImageAdapter(context, options.spanCount).apply {
            addOnSelectionListener(onSelectionListener)
            setHasStableIds(true)
        }

        binding.gridLayout.apply {
            instantRecyclerView.adapter = instantImageAdapter
            instantRecyclerView.addOnItemTouchListener(CustomItemTouchListener(binding))
            recyclerView.setupMainRecyclerView(
                context, mainImageAdapter, scrollListener(this@PixFragment, binding)
            )
        }
    }


    private fun setBottomSheetBehavior() {
        mBottomSheetBehavior = BottomSheetBehavior.from(binding.gridLayout.bottomSheet)
        requireActivity().setup(binding, mBottomSheetBehavior) {
            if (it) {
                showScrollbar(binding.gridLayout.fastscrollScrollbar, requireContext())
                mainImageAdapter.notifyDataSetChanged()
                mViewHeight = binding.gridLayout.fastscrollScrollbar.measuredHeight.toFloat()
                handler.post { binding.setViewPositions(getScrollProportion(binding.gridLayout.recyclerView)) }
                binding.gridLayout.sendButtonStateAnimation(show = false, withAnim = false)
            } else {
                instantImageAdapter.notifyDataSetChanged()
                binding.gridLayout.fastscrollScrollbar.hide()
                binding.gridLayout.sendButtonStateAnimation(model.longSelectionValue)
            }
        }
    }


    private fun CameraXManager.startCamera() {
        setUpCamera(binding)
        binding.gridLayout.controlsLayout.flashButton.show()
        binding.setDrawableIconForFlash(options)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                binding.apply {
                    if (event.x < gridLayout.fastscrollHandle.x - ViewCompat.getPaddingStart(
                            gridLayout.fastscrollHandle
                        )
                    ) {
                        return false
                    }
                    gridLayout.fastscrollHandle.isSelected = true
                    handler.removeCallbacks(mScrollbarHider)
                    cancelAnimation(mScrollbarAnimator, mBubbleAnimator)
                    if (!gridLayout.fastscrollScrollbar.isVisible && (gridLayout.recyclerView.computeVerticalScrollRange()
                                - mViewHeight > 0)
                    ) {
                        mScrollbarAnimator =
                            showScrollbar(gridLayout.fastscrollScrollbar, requireActivity())
                    }
                    showBubble()
                    val y = event.rawY
                    setViewPositions(y - toolbarHeight)
                    setRecyclerViewPosition(y)
                    v?.parent?.requestDisallowInterceptTouchEvent(true)
                }

                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val y = event.rawY
                binding.setViewPositions(y - toolbarHeight)
                binding.setRecyclerViewPosition(y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v?.parent?.requestDisallowInterceptTouchEvent(false)
                binding.gridLayout.fastscrollHandle.isSelected = false
                handler.postDelayed(mScrollbarHider, sScrollbarHideDelay.toLong())
                binding.hideBubble()
                return true
            }
        }
        return false
    }
}