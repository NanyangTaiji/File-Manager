package com.simplemobiletools.filemanager.pro.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import com.simplemobiletools.commons.adapters.MyRecyclerViewAdapter
import com.simplemobiletools.commons.dialogs.*
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FileDirItem
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.views.MyRecyclerView
import com.simplemobiletools.filemanager.pro.R
import com.simplemobiletools.filemanager.pro.activities.SimpleActivity
import com.simplemobiletools.filemanager.pro.activities.SplashActivity
import com.simplemobiletools.filemanager.pro.dialogs.CompressAsDialog
import com.simplemobiletools.filemanager.pro.extensions.*
import com.simplemobiletools.filemanager.pro.helpers.*
import com.simplemobiletools.filemanager.pro.interfaces.ItemOperationsListener
import com.simplemobiletools.filemanager.pro.models.ListItem
import com.stericson.RootTools.RootTools
import kotlinx.android.synthetic.main.item_file_dir_list.view.*
import kotlinx.android.synthetic.main.item_file_dir_list.view.item_frame
import kotlinx.android.synthetic.main.item_file_dir_list.view.item_icon
import kotlinx.android.synthetic.main.item_file_dir_list.view.item_name
import kotlinx.android.synthetic.main.item_file_grid.view.*
import kotlinx.android.synthetic.main.item_section.view.*
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class ItemsAdapter(
    activity: SimpleActivity, var listItems: MutableList<ListItem>, val listener: ItemOperationsListener?, recyclerView: MyRecyclerView,
    val isPickMultipleIntent: Boolean, val swipeRefreshLayout: SwipeRefreshLayout?, canHaveIndividualViewType: Boolean = true, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick), RecyclerViewFastScroller.OnPopupTextUpdate {

    private val TYPE_FILE = 1
    private val TYPE_DIR = 2
    private val TYPE_SECTION = 3
    private val TYPE_GRID_TYPE_DIVIDER = 4
    private lateinit var fileDrawable: Drawable
    private lateinit var folderDrawable: Drawable
    private var fileDrawables = HashMap<String, Drawable>()
    private var currentItemsHash = listItems.hashCode()
    private var textToHighlight = ""
    private val hasOTGConnected = activity.hasOTGConnected()
    private var fontSize = 0f
    private var smallerFontSize = 0f
    private var dateFormat = ""
    private var timeFormat = ""

    private val config = activity.config
    private val viewType = if (canHaveIndividualViewType) {
        config.getFolderViewType(listItems.firstOrNull { !it.isSectionTitle }?.mPath?.getParentPath() ?: "")
    } else {
        config.viewType
    }
    private val isListViewType = viewType == VIEW_TYPE_LIST
    private var displayFilenamesInGrid = config.displayFilenames

    init {
        setupDragListener(true)
        initDrawables()
        updateFontSizes()
        dateFormat = config.dateFormat
        timeFormat = activity.getTimeFormat()
    }

    override fun getActionMenuId() = R.menu.cab

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_decompress).isVisible = getSelectedFileDirItems().map { it.path }.any { it.isZipFile() }
            findItem(R.id.cab_confirm_selection).isVisible = isPickMultipleIntent
            findItem(R.id.cab_copy_path).isVisible = isOneItemSelected()
            findItem(R.id.cab_open_with).isVisible = isOneFileSelected()
            findItem(R.id.cab_open_as).isVisible = isOneFileSelected()
            findItem(R.id.cab_set_as).isVisible = isOneFileSelected()
            findItem(R.id.cab_create_shortcut).isVisible = isOreoPlus() && isOneItemSelected()

            checkHideBtnVisibility(this)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_confirm_selection -> confirmSelection()
            R.id.cab_rename -> displayRenameDialog()
            R.id.cab_properties -> showProperties()
            R.id.cab_share -> shareFiles()
            R.id.cab_hide -> toggleFileVisibility(true)
            R.id.cab_unhide -> toggleFileVisibility(false)
            R.id.cab_create_shortcut -> createShortcut()
            R.id.cab_copy_path -> copyPath()
            R.id.cab_set_as -> setAs()
            R.id.cab_open_with -> openWith()
            R.id.cab_open_as -> openAs()
            R.id.cab_copy_to -> copyMoveTo(true)
            R.id.cab_move_to -> tryMoveFiles()
            R.id.cab_compress -> compressSelection()
            R.id.cab_decompress -> decompressSelection()
            R.id.cab_select_all -> selectAll()
            R.id.cab_delete -> if (config.skipDeleteConfirmation) deleteFiles() else askConfirmDelete()
        }
    }

    override fun getSelectableItemCount() = listItems.filter { !it.isSectionTitle && !it.isGridTypeDivider }.size

    override fun getIsItemSelectable(position: Int) = !listItems[position].isSectionTitle && !listItems[position].isGridTypeDivider

    override fun getItemSelectionKey(position: Int) = listItems.getOrNull(position)?.path?.hashCode()

    override fun getItemKeyPosition(key: Int) = listItems.indexOfFirst { it.path.hashCode() == key }

    override fun onActionModeCreated() {
        swipeRefreshLayout?.isRefreshing = false
        swipeRefreshLayout?.isEnabled = false
    }

    override fun onActionModeDestroyed() {
        swipeRefreshLayout?.isEnabled = true
    }

    override fun getItemViewType(position: Int): Int {
        return when {
            listItems[position].isGridTypeDivider -> TYPE_GRID_TYPE_DIVIDER
            listItems[position].isSectionTitle -> TYPE_SECTION
            listItems[position].mIsDirectory -> TYPE_DIR
            else -> TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == TYPE_SECTION) {
            R.layout.item_section
        } else if (viewType == TYPE_GRID_TYPE_DIVIDER) {
            R.layout.item_empty
        } else {
            if (isListViewType) {
                R.layout.item_file_dir_list
            } else {
                if (viewType == TYPE_DIR) {
                    R.layout.item_dir_grid
                } else {
                    R.layout.item_file_grid
                }
            }
        }
        return createViewHolder(layout, parent)
    }

    override fun onBindViewHolder(holder: MyRecyclerViewAdapter.ViewHolder, position: Int) {
        val fileDirItem = listItems[position]
        holder.bindView(fileDirItem, true, !fileDirItem.isSectionTitle) { itemView, layoutPosition ->
            setupView(itemView, fileDirItem)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = listItems.size

    private fun getItemWithKey(key: Int): FileDirItem? = listItems.firstOrNull { it.path.hashCode() == key }

    private fun isOneFileSelected() = isOneItemSelected() && getItemWithKey(selectedKeys.first())?.isDirectory == false

    private fun checkHideBtnVisibility(menu: Menu) {
        var hiddenCnt = 0
        var unhiddenCnt = 0
        getSelectedFileDirItems().map { it.name }.forEach {
            if (it.startsWith(".")) {
                hiddenCnt++
            } else {
                unhiddenCnt++
            }
        }

        menu.findItem(R.id.cab_hide).isVisible = unhiddenCnt > 0
        menu.findItem(R.id.cab_unhide).isVisible = hiddenCnt > 0
    }

    private fun confirmSelection() {
        if (selectedKeys.isNotEmpty()) {
            val paths = getSelectedFileDirItems().asSequence().filter { !it.isDirectory }.map { it.path }.toMutableList() as ArrayList<String>
            if (paths.isEmpty()) {
                finishActMode()
            } else {
                listener?.selectedPaths(paths)
            }
        }
    }

    private fun displayRenameDialog() {
        val fileDirItems = getSelectedFileDirItems()
        val paths = fileDirItems.asSequence().map { it.path }.toMutableList() as ArrayList<String>
        when {
            paths.size == 1 -> {
                val oldPath = paths.first()
                RenameItemDialog(activity, oldPath) {
                    config.moveFavorite(oldPath, it)
                    activity.runOnUiThread {
                        listener?.refreshFragment()
                        finishActMode()
                    }
                }
            }

            fileDirItems.any { it.isDirectory } -> RenameItemsDialog(activity, paths) {
                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }

            else -> RenameDialog(activity, paths, false) {
                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }
        }
    }

    private fun showProperties() {
        if (selectedKeys.size <= 1) {
            PropertiesDialog(activity, getFirstSelectedItemPath(), config.shouldShowHidden())
        } else {
            val paths = getSelectedFileDirItems().map { it.path }
            PropertiesDialog(activity, paths, config.shouldShowHidden())
        }
    }

    private fun shareFiles() {
        val selectedItems = getSelectedFileDirItems()
        val paths = ArrayList<String>(selectedItems.size)
        selectedItems.forEach {
            addFileUris(it.path, paths)
        }
        activity.sharePaths(paths)
    }

    private fun toggleFileVisibility(hide: Boolean) {
        ensureBackgroundThread {
            getSelectedFileDirItems().forEach {
                activity.toggleItemVisibility(it.path, hide)
            }
            activity.runOnUiThread {
                listener?.refreshFragment()
                finishActMode()
            }
        }
    }

    @SuppressLint("NewApi")
    private fun createShortcut() {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager.isRequestPinShortcutSupported) {
            val path = getFirstSelectedItemPath()
            val drawable = resources.getDrawable(R.drawable.shortcut_folder).mutate()
            getShortcutImage(path, drawable) {
                val intent = Intent(activity, SplashActivity::class.java)
                intent.action = Intent.ACTION_VIEW
                intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                intent.data = Uri.fromFile(File(path))

                val shortcut = ShortcutInfo.Builder(activity, path)
                    .setShortLabel(path.getFilenameFromPath())
                    .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)
            }
        }
    }

    private fun getShortcutImage(path: String, drawable: Drawable, callback: () -> Unit) {
        val appIconColor = baseConfig.appIconColor
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_folder_background).applyColorFilter(appIconColor)
        if (activity.getIsPathDirectory(path)) {
            callback()
        } else {
            ensureBackgroundThread {
                val options = RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .fitCenter()

                val size = activity.resources.getDimension(R.dimen.shortcut_size).toInt()
                val builder = Glide.with(activity)
                    .asDrawable()
                    .load(getImagePathToLoad(path))
                    .apply(options)
                    .centerCrop()
                    .into(size, size)

                try {
                    val bitmap = builder.get()
                    drawable.findDrawableByLayerId(R.id.shortcut_folder_background).applyColorFilter(0)
                    drawable.setDrawableByLayerId(R.id.shortcut_folder_image, bitmap)
                } catch (e: Exception) {
                    val fileIcon = fileDrawables.getOrElse(path.substringAfterLast(".").toLowerCase(), { fileDrawable })
                    drawable.setDrawableByLayerId(R.id.shortcut_folder_image, fileIcon)
                }

                activity.runOnUiThread {
                    callback()
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun addFileUris(path: String, paths: ArrayList<String>) {
        if (activity.getIsPathDirectory(path)) {
            val shouldShowHidden = config.shouldShowHidden()
            when {
                activity.isRestrictedSAFOnlyRoot(path) -> {
                    activity.getAndroidSAFFileItems(path, shouldShowHidden, false) { files ->
                        files.forEach {
                            addFileUris(activity.getAndroidSAFUri(it.path).toString(), paths)
                        }
                    }
                }

                activity.isPathOnOTG(path) -> {
                    activity.getDocumentFile(path)?.listFiles()?.filter { if (shouldShowHidden) true else !it.name!!.startsWith(".") }?.forEach {
                        addFileUris(it.uri.toString(), paths)
                    }
                }

                else -> {
                    File(path).listFiles()?.filter { if (shouldShowHidden) true else !it.name.startsWith('.') }?.forEach {
                        addFileUris(it.absolutePath, paths)
                    }
                }
            }
        } else {
            paths.add(path)
        }
    }

    private fun copyPath() {
        activity.copyToClipboard(getFirstSelectedItemPath())
        finishActMode()
    }

    private fun setAs() {
        activity.setAs(getFirstSelectedItemPath())
    }

    private fun openWith() {
        activity.tryOpenPathIntent(getFirstSelectedItemPath(), true)
    }

    private fun openAs() {
        val res = activity.resources
        val items = arrayListOf(
            RadioItem(OPEN_AS_TEXT, res.getString(R.string.text_file)),
            RadioItem(OPEN_AS_IMAGE, res.getString(R.string.image_file)),
            RadioItem(OPEN_AS_AUDIO, res.getString(R.string.audio_file)),
            RadioItem(OPEN_AS_VIDEO, res.getString(R.string.video_file)),
            RadioItem(OPEN_AS_OTHER, res.getString(R.string.other_file))
        )

        RadioGroupDialog(activity, items) {
            activity.tryOpenPathIntent(getFirstSelectedItemPath(), false, it as Int)
        }
    }

    private fun tryMoveFiles() {
        activity.handleDeletePasswordProtection {
            copyMoveTo(false)
        }
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val files = getSelectedFileDirItems()
        val firstFile = files[0]
        val source = firstFile.getParentPath()
        FilePickerDialog(
            activity,
            activity.getDefaultCopyDestinationPath(config.shouldShowHidden(), source),
            false,
            config.shouldShowHidden(),
            true,
            true,
            showFavoritesButton = true
        ) {
            config.lastCopyPath = it
            if (activity.isPathOnRoot(it) || activity.isPathOnRoot(firstFile.path)) {
                copyMoveRootItems(files, it, isCopyOperation)
            } else {
                activity.copyMoveFilesTo(files, source, it, isCopyOperation, false, config.shouldShowHidden()) {
                    if (!isCopyOperation) {
                        files.forEach { sourceFileDir ->
                            val sourcePath = sourceFileDir.path
                            if (activity.isRestrictedSAFOnlyRoot(sourcePath) && activity.getDoesFilePathExist(sourcePath)) {
                                activity.deleteFile(sourceFileDir, true) {
                                    listener?.refreshFragment()
                                    activity.runOnUiThread {
                                        finishActMode()
                                    }
                                }
                            } else {
                                val sourceFile = File(sourcePath)
                                if (activity.getDoesFilePathExist(source) && activity.getIsPathDirectory(source) &&
                                    sourceFile.list()?.isEmpty() == true && sourceFile.getProperSize(true) == 0L && sourceFile.getFileCount(true) == 0
                                ) {
                                    val sourceFolder = sourceFile.toFileDirItem(activity)
                                    activity.deleteFile(sourceFolder, true) {
                                        listener?.refreshFragment()
                                        activity.runOnUiThread {
                                            finishActMode()
                                        }
                                    }
                                } else {
                                    listener?.refreshFragment()
                                    finishActMode()
                                }
                            }
                        }
                    } else {
                        listener?.refreshFragment()
                        finishActMode()
                    }
                }
            }
        }
    }

    private fun copyMoveRootItems(files: ArrayList<FileDirItem>, destinationPath: String, isCopyOperation: Boolean) {
        activity.toast(R.string.copying)
        ensureBackgroundThread {
            val fileCnt = files.size
            RootHelpers(activity).copyMoveFiles(files, destinationPath, isCopyOperation) {
                when (it) {
                    fileCnt -> activity.toast(R.string.copying_success)
                    0 -> activity.toast(R.string.copy_failed)
                    else -> activity.toast(R.string.copying_success_partial)
                }

                activity.runOnUiThread {
                    listener?.refreshFragment()
                    finishActMode()
                }
            }
        }
    }

    private fun compressSelection() {
        val firstPath = getFirstSelectedItemPath()
        if (activity.isPathOnOTG(firstPath)) {
            activity.toast(R.string.unknown_error_occurred)
            return
        }

        CompressAsDialog(activity, firstPath) { destination, password ->
            activity.handleAndroidSAFDialog(firstPath) { granted ->
                if (!granted) {
                    return@handleAndroidSAFDialog
                }
                activity.handleSAFDialog(firstPath) {
                    if (!it) {
                        return@handleSAFDialog
                    }

                    activity.toast(R.string.compressing)
                    val paths = getSelectedFileDirItems().map { it.path }
                    ensureBackgroundThread {
                        if (compressPaths(paths, destination, password)) {
                            activity.runOnUiThread {
                                activity.toast(R.string.compression_successful)
                                listener?.refreshFragment()
                                finishActMode()
                            }
                        } else {
                            activity.toast(R.string.compressing_failed)
                        }
                    }
                }
            }
        }
    }

    private fun decompressSelection() {
        val firstPath = getFirstSelectedItemPath()
        if (activity.isPathOnOTG(firstPath)) {
            activity.toast(R.string.unknown_error_occurred)
            return
        }

        activity.handleSAFDialog(firstPath) {
            if (!it) {
                return@handleSAFDialog
            }

            val paths = getSelectedFileDirItems().asSequence().map { it.path }.filter { it.isZipFile() }.toList()
            ensureBackgroundThread {
                tryDecompressingPaths(paths) { success ->
                    activity.runOnUiThread {
                        if (success) {
                            activity.toast(R.string.decompression_successful)
                            listener?.refreshFragment()
                            finishActMode()
                        } else {
                            activity.toast(R.string.decompressing_failed)
                        }
                    }
                }
            }
        }
    }

    private fun tryDecompressingPaths(sourcePaths: List<String>, callback: (success: Boolean) -> Unit) {
        sourcePaths.forEach { path ->
            ZipInputStream(BufferedInputStream(activity.getFileInputStreamSync(path))).use { zipInputStream ->
                try {
                    val fileDirItems = ArrayList<FileDirItem>()
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        val currPath = if (entry.isDirectory) path else "${path.getParentPath().trimEnd('/')}/${entry.name}"
                        val fileDirItem = FileDirItem(currPath, entry.name, entry.isDirectory, 0, entry.size)
                        fileDirItems.add(fileDirItem)
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                    zipInputStream.closeEntry()
                    val destinationPath = fileDirItems.first().getParentPath().trimEnd('/')
                    activity.runOnUiThread {
                        activity.checkConflicts(fileDirItems, destinationPath, 0, LinkedHashMap()) {
                            ensureBackgroundThread {
                                decompressPaths(sourcePaths, it, callback)
                            }
                        }
                    }
                } catch (exception: Exception) {
                    activity.showErrorToast(exception)
                }
            }
        }
    }

    private fun decompressPaths(paths: List<String>, conflictResolutions: LinkedHashMap<String, Int>, callback: (success: Boolean) -> Unit) {
        paths.forEach { path ->
            val zipInputStream = ZipInputStream(BufferedInputStream(activity.getFileInputStreamSync(path)))
            zipInputStream.use {
                try {
                    var entry = zipInputStream.nextEntry
                    val zipFileName = path.getFilenameFromPath()
                    val newFolderName = zipFileName.subSequence(0, zipFileName.length - 4)
                    while (entry != null) {
                        val parentPath = path.getParentPath()
                        val newPath = "$parentPath/$newFolderName/${entry.name.trimEnd('/')}"

                        val resolution = getConflictResolution(conflictResolutions, newPath)
                        val doesPathExist = activity.getDoesFilePathExist(newPath)
                        if (doesPathExist && resolution == CONFLICT_OVERWRITE) {
                            val fileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath(), entry.isDirectory)
                            if (activity.getIsPathDirectory(path)) {
                                activity.deleteFolderBg(fileDirItem, false) {
                                    if (it) {
                                        extractEntry(newPath, entry, zipInputStream)
                                    } else {
                                        callback(false)
                                    }
                                }
                            } else {
                                activity.deleteFileBg(fileDirItem, false, false) {
                                    if (it) {
                                        extractEntry(newPath, entry, zipInputStream)
                                    } else {
                                        callback(false)
                                    }
                                }
                            }
                        } else if (!doesPathExist) {
                            extractEntry(newPath, entry, zipInputStream)
                        }

                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                    callback(true)
                } catch (e: Exception) {
                    activity.showErrorToast(e)
                    callback(false)
                }
            }
        }
    }

    private fun extractEntry(newPath: String, entry: ZipEntry, zipInputStream: ZipInputStream) {
        if (entry.isDirectory) {
            if (!activity.createDirectorySync(newPath) && !activity.getDoesFilePathExist(newPath)) {
                val error = String.format(activity.getString(R.string.could_not_create_file), newPath)
                activity.showErrorToast(error)
            }
        } else {
            val fos = activity.getFileOutputStreamSync(newPath, newPath.getMimeType())
            if (fos != null) {
                zipInputStream.copyTo(fos)
            }
        }
    }

    private fun getConflictResolution(conflictResolutions: LinkedHashMap<String, Int>, path: String): Int {
        return if (conflictResolutions.size == 1 && conflictResolutions.containsKey("")) {
            conflictResolutions[""]!!
        } else if (conflictResolutions.containsKey(path)) {
            conflictResolutions[path]!!
        } else {
            CONFLICT_SKIP
        }
    }

    @SuppressLint("NewApi")
    private fun compressPaths(sourcePaths: List<String>, targetPath: String, password: String? = null): Boolean {
        val queue = LinkedList<String>()
        val fos = activity.getFileOutputStreamSync(targetPath, "application/zip") ?: return false

        val zout = password?.let { ZipOutputStream(fos, password.toCharArray()) } ?: ZipOutputStream(fos)
        var res: Closeable = fos

        fun zipEntry(name: String) = ZipParameters().also {
            it.fileNameInZip = name
            if (password != null) {
                it.isEncryptFiles = true
                it.encryptionMethod = EncryptionMethod.AES
            }
        }

        try {
            sourcePaths.forEach { currentPath ->
                var name: String
                var mainFilePath = currentPath
                val base = "${mainFilePath.getParentPath()}/"
                res = zout
                queue.push(mainFilePath)
                if (activity.getIsPathDirectory(mainFilePath)) {
                    name = "${mainFilePath.getFilenameFromPath()}/"
                    zout.putNextEntry(
                        ZipParameters().also {
                            it.fileNameInZip = name
                        }
                    )
                }

                while (!queue.isEmpty()) {
                    mainFilePath = queue.pop()
                    if (activity.getIsPathDirectory(mainFilePath)) {
                        if (activity.isRestrictedSAFOnlyRoot(mainFilePath)) {
                            activity.getAndroidSAFFileItems(mainFilePath, true) { files ->
                                for (file in files) {
                                    name = file.path.relativizeWith(base)
                                    if (activity.getIsPathDirectory(file.path)) {
                                        queue.push(file.path)
                                        name = "${name.trimEnd('/')}/"
                                        zout.putNextEntry(zipEntry(name))
                                    } else {
                                        zout.putNextEntry(zipEntry(name))
                                        activity.getFileInputStreamSync(file.path)!!.copyTo(zout)
                                        zout.closeEntry()
                                    }
                                }
                            }
                        } else {
                            val mainFile = File(mainFilePath)
                            for (file in mainFile.listFiles()) {
                                name = file.path.relativizeWith(base)
                                if (activity.getIsPathDirectory(file.absolutePath)) {
                                    queue.push(file.absolutePath)
                                    name = "${name.trimEnd('/')}/"
                                    zout.putNextEntry(zipEntry(name))
                                } else {
                                    zout.putNextEntry(zipEntry(name))
                                    activity.getFileInputStreamSync(file.path)!!.copyTo(zout)
                                    zout.closeEntry()
                                }
                            }
                        }

                    } else {
                        name = if (base == currentPath) currentPath.getFilenameFromPath() else mainFilePath.relativizeWith(base)
                        zout.putNextEntry(zipEntry(name))
                        activity.getFileInputStreamSync(mainFilePath)!!.copyTo(zout)
                        zout.closeEntry()
                    }
                }
            }
        } catch (exception: Exception) {
            activity.showErrorToast(exception)
            return false
        } finally {
            res.close()
        }
        return true
    }

    private fun askConfirmDelete() {
        activity.handleDeletePasswordProtection {
            val itemsCnt = selectedKeys.size
            val items = if (itemsCnt == 1) {
                "\"${getFirstSelectedItemPath().getFilenameFromPath()}\""
            } else {
                resources.getQuantityString(R.plurals.delete_items, itemsCnt, itemsCnt)
            }

            val question = String.format(resources.getString(R.string.deletion_confirmation), items)
            ConfirmationDialog(activity, question) {
                deleteFiles()
            }
        }
    }

    private fun deleteFiles() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val SAFPath = getFirstSelectedItemPath()
        if (activity.isPathOnRoot(SAFPath) && !RootTools.isRootAvailable()) {
            activity.toast(R.string.rooted_device_only)
            return
        }

        activity.handleSAFDialog(SAFPath) {
            if (!it) {
                return@handleSAFDialog
            }

            val files = ArrayList<FileDirItem>(selectedKeys.size)
            val positions = ArrayList<Int>()
            selectedKeys.forEach {
                config.removeFavorite(getItemWithKey(it)?.path ?: "")
                val key = it
                val position = listItems.indexOfFirst { it.path.hashCode() == key }
                if (position != -1) {
                    positions.add(position)
                    files.add(listItems[position])
                }
            }

            positions.sortDescending()
            removeSelectedItems(positions)
            listener?.deleteFiles(files)
            positions.forEach {
                listItems.removeAt(it)
            }
        }
    }

    private fun getFirstSelectedItemPath() = getSelectedFileDirItems().first().path

    private fun getSelectedFileDirItems() = listItems.filter { selectedKeys.contains(it.path.hashCode()) } as ArrayList<FileDirItem>

    fun updateItems(newItems: ArrayList<ListItem>, highlightText: String = "") {
        if (newItems.hashCode() != currentItemsHash) {
            currentItemsHash = newItems.hashCode()
            textToHighlight = highlightText
            listItems = newItems.clone() as ArrayList<ListItem>
            notifyDataSetChanged()
            finishActMode()
        } else if (textToHighlight != highlightText) {
            textToHighlight = highlightText
            notifyDataSetChanged()
        }
    }

    fun updateFontSizes() {
        fontSize = activity.getTextSize()
        smallerFontSize = fontSize * 0.8f
        notifyDataSetChanged()
    }

    fun updateDateTimeFormat() {
        dateFormat = config.dateFormat
        timeFormat = activity.getTimeFormat()
        notifyDataSetChanged()
    }

    fun updateDisplayFilenamesInGrid() {
        displayFilenamesInGrid = config.displayFilenames
        notifyDataSetChanged()
    }

    fun updateChildCount(path: String, count: Int) {
        val position = getItemKeyPosition(path.hashCode())
        val item = listItems.getOrNull(position) ?: return
        item.children = count
        notifyItemChanged(position, Unit)
    }

    fun isASectionTitle(position: Int) = listItems.getOrNull(position)?.isSectionTitle ?: false

    fun isGridTypeDivider(position: Int) = listItems.getOrNull(position)?.isGridTypeDivider ?: false

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val icon = holder.itemView.item_icon
            if (icon != null) {
                Glide.with(activity).clear(icon)
            }
        }
    }

    private fun setupView(view: View, listItem: ListItem) {
        val isSelected = selectedKeys.contains(listItem.path.hashCode())
        view.apply {
            if (listItem.isSectionTitle) {
                item_icon.setImageDrawable(folderDrawable)
                item_section.text = if (textToHighlight.isEmpty()) listItem.mName else listItem.mName.highlightTextPart(textToHighlight, properPrimaryColor)
                item_section.setTextColor(textColor)
                item_section.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            } else if (!listItem.isGridTypeDivider) {
                setupViewBackground(activity)
                item_frame.isSelected = isSelected
                val fileName = listItem.name
                item_name.text = if (textToHighlight.isEmpty()) fileName else fileName.highlightTextPart(textToHighlight, properPrimaryColor)
                item_name.setTextColor(textColor)
                item_name.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (isListViewType) fontSize else smallerFontSize)

                item_details?.setTextColor(textColor)
                item_details?.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)

                item_date?.setTextColor(textColor)
                item_date?.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallerFontSize)

                item_check?.beVisibleIf(isSelected)
                if (isSelected) {
                    item_check?.background?.applyColorFilter(properPrimaryColor)
                    item_check?.applyColorFilter(contrastColor)
                }

                if (!isListViewType && !listItem.isDirectory) {
                    item_name.beVisibleIf(displayFilenamesInGrid)
                } else {
                    item_name.beVisible()
                }

                if (listItem.isDirectory) {
                    item_icon.setImageDrawable(folderDrawable)
                    item_details?.text = getChildrenCnt(listItem)
                    item_date?.beGone()
                } else {
                    item_details?.text = listItem.size.formatSize()
                    item_date?.beVisible()
                    item_date?.text = listItem.modified.formatDate(activity, dateFormat, timeFormat)

                    val drawable = fileDrawables.getOrElse(fileName.substringAfterLast(".").toLowerCase(), { fileDrawable })
                    val options = RequestOptions()
                        .signature(listItem.getKey())
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .error(drawable)
                        .transform(CenterCrop(), RoundedCorners(10))

                    val itemToLoad = getImagePathToLoad(listItem.path)
                    if (!activity.isDestroyed) {
                        Glide.with(activity)
                            .load(itemToLoad)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .apply(options)
                            .into(item_icon)
                    }
                }
            }
        }
    }

    private fun getChildrenCnt(item: FileDirItem): String {
        val children = item.children
        return activity.resources.getQuantityString(R.plurals.items, children, children)
    }

    private fun getOTGPublicPath(itemToLoad: String) =
        "${baseConfig.OTGTreeUri}/document/${baseConfig.OTGPartition}%3A${itemToLoad.substring(baseConfig.OTGPath.length).replace("/", "%2F")}"

    private fun getImagePathToLoad(path: String): Any {
        var itemToLoad = if (path.endsWith(".apk", true)) {
            val packageInfo = activity.packageManager.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES)
            if (packageInfo != null) {
                val appInfo = packageInfo.applicationInfo
                appInfo.sourceDir = path
                appInfo.publicSourceDir = path
                appInfo.loadIcon(activity.packageManager)
            } else {
                path
            }
        } else {
            path
        }

        if (activity.isRestrictedSAFOnlyRoot(path)) {
            itemToLoad = activity.getAndroidSAFUri(path)
        } else if (hasOTGConnected && itemToLoad is String && activity.isPathOnOTG(itemToLoad) && baseConfig.OTGTreeUri.isNotEmpty() && baseConfig.OTGPartition.isNotEmpty()) {
            itemToLoad = getOTGPublicPath(itemToLoad)
        }

        return itemToLoad
    }

    fun initDrawables() {
        folderDrawable = resources.getColoredDrawableWithColor(R.drawable.ic_folder_vector, textColor)
        folderDrawable.alpha = 180
        fileDrawable = resources.getDrawable(R.drawable.ic_file_generic)
        fileDrawables = getFilePlaceholderDrawables(activity)
    }

    override fun onChange(position: Int) = listItems.getOrNull(position)?.getBubbleText(activity, dateFormat, timeFormat) ?: ""
}
