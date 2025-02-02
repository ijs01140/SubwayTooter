package jp.juggler.util.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RawRes
import jp.juggler.util.log.LogCategory
import okhttp3.internal.closeQuietly
import java.io.InputStream

private val log = LogCategory("StorageUtils")

// internal object StorageUtils{
//
//	private val log = LogCategory("StorageUtils")
//
//	private const val PATH_TREE = "tree"
//	private const val PATH_DOCUMENT = "document"
//
//	internal class FileInfo(any_uri : String?) {
//
//		var uri : Uri? = null
//		private var mime_type : String? = null
//
//		init {
//			if(any_uri != null) {
//				uri = if(any_uri.startsWith("/")) {
//					Uri.fromFile(File(any_uri))
//				} else {
//					any_uri.toUri()
//				}
//				val ext = MimeTypeMap.getFileExtensionFromUrl(any_uri)
//				if(ext != null) {
//					mime_type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.JAPAN))
//				}
//			}
//		}
//	}
//
//	private fun getSecondaryStorageVolumesMap(context : Context) : Map<String, String> {
//		val result = HashMap<String, String>()
//		try {
//			val sm = context.applicationContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
//			if(sm == null) {
//				log.e("can't get StorageManager")
//			} else {
//
//				// SDカードスロットのある7.0端末が手元にないから検証できない
//				//			if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ){
//				//				for(StorageVolume volume : sm.getStorageVolumes() ){
//				//					// String path = volume.getPath();
//				//					String state = volume.getState();
//				//
//				//				}
//				//			}
//
//				val getVolumeList = sm.javaClass.getMethod("getVolumeList")
//				val volumes = getVolumeList.invoke(sm)
//				log.d("volumes type=%s", volumes.javaClass)
//
//				if(volumes is ArrayList<*>) {
//					//
//					for(volume in volumes) {
//						val volume_clazz = volume.javaClass
//
//						val path = volume_clazz.getMethod("getPath").invoke(volume) as? String
//						val state = volume_clazz.getMethod("getState").invoke(volume) as? String
//						if(path != null && state == "mounted") {
//							//
//							val isPrimary = volume_clazz.getMethod("isPrimary").invoke(volume) as? Boolean
//							if(isPrimary == true) result["primary"] = path
//							//
//							val uuid = volume_clazz.getMethod("getUuid").invoke(volume) as? String
//							if(uuid != null) result[uuid] = path
//						}
//					}
//				}
//			}
//		} catch(ex : Throwable) {
//			log.trace(ex)
//		}
//
//		return result
//	}
//
//	private fun isExternalStorageDocument(uri : Uri) : Boolean {
//		return "com.android.externalstorage.documents" == uri.authority
//	}
//
//	private fun getDocumentId(documentUri : Uri) : String {
//		val paths = documentUri.pathSegments
//		if(paths.size >= 2 && PATH_DOCUMENT == paths[0]) {
//			// document
//			return paths[1]
//		}
//		if(paths.size >= 4 && PATH_TREE == paths[0]
//			&& PATH_DOCUMENT == paths[2]) {
//			// document in tree
//			return paths[3]
//		}
//		if(paths.size >= 2 && PATH_TREE == paths[0]) {
//			// tree
//			return paths[1]
//		}
//		throw IllegalArgumentException("Invalid URI: $documentUri")
//	}

//	fun getFile(context : Context, path : String) : File? {
//		try {
//			if(path.startsWith("/")) return File(path)
//			val uri = path.toUri()
//			if("file" == uri.scheme) return File(uri.path!!)
//
//			// if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT )
//			run {
//				if(isExternalStorageDocument(uri)) {
//					try {
//						val docId = getDocumentId(uri)
//						val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
//						if(split.size >= 2) {
//							val uuid = split[0]
//							if("primary".equals(uuid, ignoreCase = true)) {
//								return File(Environment.getExternalStorageDirectory().toString() + "/" + split[1])
//							} else {
//								val volume_map =
//									getSecondaryStorageVolumesMap(
//										context
//									)
//								val volume_path = volume_map[uuid]
//								if(volume_path != null) {
//									return File(volume_path + "/" + split[1])
//								}
//							}
//						}
//					} catch(ex : Throwable) {
//						log.trace(ex)
//					}
//
//				}
//			}
//			// MediaStore Uri
//			context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
//				if(cursor.moveToFirst()) {
//					val col_count = cursor.columnCount
//					for(i in 0 until col_count) {
//						val type = cursor.getType(i)
//						if(type != Cursor.FIELD_TYPE_STRING) continue
//						val name = cursor.getColumnName(i)
//						val value = cursor.getStringOrNull(i)
//						if(value != null && value.isNotEmpty() && "filePath" == name) return File(value)
//					}
//				}
//			}
//		} catch(ex : Throwable) {
//			log.trace(ex)
//		}
//
//		return null
//	}
//

//
//
//}

private const val MIME_TYPE_APPLICATION_OCTET_STREAM = "application/octet-stream"

private val mimeTypeExMap: HashMap<String, String> by lazy {
    val map = HashMap<String, String>()
    map["BDM"] = "application/vnd.syncml.dm+wbxml"
    map["DAT"] = ""
    map["TID"] = ""
    map["js"] = "text/javascript"
    map["sh"] = "application/x-sh"
    map["lua"] = "text/x-lua"
    map
}

@Suppress("unused")
fun getMimeType(log: LogCategory?, src: String): String {
    MimeTypeMap.getFileExtensionFromUrl(src)
        ?.notEmpty()?.lowercase()?.let { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?.notEmpty()?.let { return it }

            val mimeType = mimeTypeExMap[ext]
            mimeType?.notEmpty()?.let { return it }

            // 戻り値が空文字列の場合とnullの場合があり、空文字列の場合は既知なのでログ出力しない
            if (mimeType == null && log != null) {
                log.w("getMimeType(): unknown file extension '$ext'")
            }
        }

    return MIME_TYPE_APPLICATION_OCTET_STREAM
}

fun getDocumentName(contentResolver: ContentResolver, uri: Uri): String {
    val errorName = "no_name"
    return contentResolver.query(uri, null, null, null, null, null)
        ?.use { cursor ->
            return if (!cursor.moveToFirst()) {
                errorName
            } else {
                cursor.getStringOrNull(OpenableColumns.DISPLAY_NAME) ?: errorName
            }
        }
        ?: errorName
}

fun getStreamSize(bClose: Boolean, inStream: InputStream): Long {
    try {
        var size = 0L
        val tmpBuffer = ByteArray(0x10000)
        while (true) {
            val delta = inStream.read(tmpBuffer, 0, tmpBuffer.size)
            if (delta <= 0) break
            size += delta.toLong()
        }
        return size
    } finally {
        if (bClose) inStream.closeQuietly()
    }
}

//fun File.loadByteArray() : ByteArray {
//	val size = this.length().toInt()
//	val data = ByteArray(size)
//	FileInputStream(this).use { inStream ->
//		val nRead = 0
//		while(nRead < size) {
//			val delta = inStream.read(data, nRead, size - nRead)
//			if(delta <= 0) break
//		}
//		return data
//	}
//}

fun Context.loadRawResource(@RawRes resId: Int): ByteArray {
    return resources.openRawResource(resId).use { it.readBytes() }
}

fun intentOpenDocument(mimeType: String): Intent {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)
    intent.type = mimeType // "image/*"
    return intent
}

fun intentGetContent(
    allowMultiple: Boolean,
    caption: String,
    mimeTypes: Array<out String>,
): Intent {
    val intent = Intent(Intent.ACTION_GET_CONTENT)
    intent.addCategory(Intent.CATEGORY_OPENABLE)

    if (allowMultiple) {
        // EXTRA_ALLOW_MULTIPLE は API 18 (4.3)以降。ACTION_GET_CONTENT でも ACTION_OPEN_DOCUMENT でも指定できる
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    // EXTRA_MIME_TYPES は API 19以降。ACTION_GET_CONTENT でも ACTION_OPEN_DOCUMENT でも指定できる
    intent.putExtra("android.intent.extra.MIME_TYPES", mimeTypes)

    intent.type = when (mimeTypes.size) {
        1 -> mimeTypes[0]

        // On Android 6.0 and above using "video/* image/" or "image/ video/*" type doesn't work
        // it only recognizes the first filter you specify.
        else -> "*/*"
    }

    return Intent.createChooser(intent, caption)
}

data class GetContentResultEntry(
    val uri: Uri,
    val mimeType: String? = null,
    var time: Long? = null,
)

// returns list of pair of uri and mime-type.
fun Intent.handleGetContentResult(contentResolver: ContentResolver): ArrayList<GetContentResultEntry> {
    val urlList = ArrayList<GetContentResultEntry>()
    // 単一選択
    data?.let {
        val mimeType = try {
            type ?: contentResolver.getType(it)
        } catch (ex: Throwable) {
            log.w(ex, "contentResolver.getType failed. uri=$it")
            null
        }
        urlList.add(GetContentResultEntry(it, mimeType))
    }
    // 複数選択
    this.clipData?.let { clipData ->
        for (uri in (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it)?.uri }) {
            if (urlList.none { it.uri == uri }) {
                urlList.add(GetContentResultEntry(uri))
            }
        }
    }
    urlList.forEach {
        try {
            contentResolver.takePersistableUriPermission(
                it.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
    }
    return urlList
}
