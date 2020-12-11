package jp.juggler.subwaytooter.api

import jp.juggler.util.*
import okhttp3.Response
import okhttp3.WebSocket

open class TootApiResult(
	@Suppress("unused") val dummy : Int = 0,
	var error : String? = null,
	var response : Response? = null,
	var bodyString : String? = null
) {
	
	companion object {
		
		private val log = LogCategory("TootApiResult")
		
		private val reLinkURL = """<([^>]+)>;\s*rel="([^"]+)"""".asciiPattern()
		
		fun makeWithCaption(caption : String?) : TootApiResult {
			val result = TootApiResult()
			if(caption?.isEmpty() != false) {
				log.e("makeWithCaption: missing caption!")
				result.error = "missing instance name"
			} else {
				result.caption = caption
			}
			return result
		}
	}
	
	var requestInfo = ""
	
	var tokenInfo : JsonObject? = null
	
	var data : Any? = null
		set(value) {
			if(value is JsonArray) {
				parseLinkHeader(response, value)
			}
			field = value
		}
	
	val jsonObject : JsonObject?
		get() = data as? JsonObject
	
	val jsonArray : JsonArray?
		get() = data as? JsonArray
	
	val string : String?
		get() = data as? String
	
	var link_older : String? = null // より古いデータへのリンク
	var link_newer : String? = null // より新しいデータへの
	var caption : String = "?"
	
	constructor() : this(0)
	
	constructor(error : String) : this(0, error = error)
	
	constructor(socket : WebSocket) : this(0) {
		this.data = socket
	}
	
	constructor(response : Response, error : String)
		: this(0, error, response)
	
	constructor(response : Response, bodyString : String, data : Any?)
		: this(0, response = response, bodyString = bodyString) {
		this.data = data
	}
	
	// return result.setError(...) と書きたい
	fun setError(error : String) : TootApiResult {
		this.error = error
		return this
	}
	
	private fun parseLinkHeader(response : Response?, array : JsonArray) {
		response ?: return
		
		log.d("array size=${array.size}")
		
		val sv = response.header("Link")
		if(sv == null) {
			log.d("missing Link header")
		} else {
			// Link:  <https://mastodon.juggler.jp/api/v1/timelines/home?limit=XX&max_id=405228>; rel="next",
			//        <https://mastodon.juggler.jp/api/v1/timelines/home?limit=XX&since_id=436946>; rel="prev"
			val m = reLinkURL.matcher(sv)
			while(m.find()) {
				val url = m.groupEx(1)
				val rel = m.groupEx(2)
				//	warning.d("Link %s,%s",rel,url);
				if("next" == rel) link_older = url
				if("prev" == rel) link_newer = url
			}
		}
	}
}
