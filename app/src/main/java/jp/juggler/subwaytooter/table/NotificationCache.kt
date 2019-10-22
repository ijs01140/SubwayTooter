package jp.juggler.subwaytooter.table

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import jp.juggler.subwaytooter.App1
import jp.juggler.subwaytooter.Column
import jp.juggler.subwaytooter.api.TootApiClient
import jp.juggler.subwaytooter.api.TootApiResult
import jp.juggler.subwaytooter.api.entity.EntityId
import jp.juggler.subwaytooter.api.entity.TootNotification
import jp.juggler.subwaytooter.api.entity.TootStatus
import jp.juggler.util.*
import org.json.JSONObject
import java.util.*
import kotlin.Comparator
import kotlin.collections.ArrayList

class NotificationCache(private val account_db_id : Long) {
	
	private var id = - 1L
	
	// サーバから通知を取得した時刻
	private var last_load : Long = 0
	
	// 通知のリスト
	var data = ArrayList<JSONObject>()
	
	var sinceId : EntityId? = null
	
	companion object : TableCompanion {
		
		private val log = LogCategory("NotificationCache")
		
		private const val table = "noti_cache"
		
		private const val COL_ID = BaseColumns._ID
		
		// アカウントDBの行ID。 サーバ側のIDではない
		private const val COL_ACCOUNT_DB_ID = "a"
		
		// サーバから通知を取得した時刻
		private const val COL_LAST_LOAD = "l"
		
		// サーバから最後に読んだデータ。既読は排除されてるかも
		private const val COL_DATA = "d"
		
		// サーバから最後に読んだデータ。既読は排除されてるかも
		private const val COL_SINCE_ID = "si"
		
		override fun onDBCreate(db : SQLiteDatabase) {
			
			db.execSQL(
				"""
				create table if not exists $table
				($COL_ID INTEGER PRIMARY KEY
				,$COL_ACCOUNT_DB_ID integer not null
				,$COL_LAST_LOAD integer default 0
				,$COL_DATA text
				,$COL_SINCE_ID text
				)
				"""
			)
			db.execSQL(
				"create unique index if not exists ${table}_a on $table ($COL_ACCOUNT_DB_ID)"
			)
		}
		
		override fun onDBUpgrade(db : SQLiteDatabase, oldVersion : Int, newVersion : Int) {
			if(oldVersion < 41 && newVersion >= 41) {
				onDBCreate(db)
				return
			}
		}
		
		private const val WHERE_AID = "$COL_ACCOUNT_DB_ID=?"
		
		private const val KEY_TIME_CREATED_AT = "<>KEY_TIME_CREATED_AT"
		
		//		fun updateRead(account_db_id : Long) {
		//			try {
		//				val where_args = arrayOf(account_db_id.toString())
		//				App1.database.query(
		//					table,
		//					arrayOf(COL_NID_SHOW, COL_NID_READ),
		//					WHERE_AID,
		//					where_args,
		//					null,
		//					null,
		//					null
		//				)?.use { cursor ->
		//					when {
		//						! cursor.moveToFirst() -> log.e("updateRead[${account_db_id}]: can't find the data row.")
		//
		//						else -> {
		//							val nid_show = EntityId.from(cursor, COL_NID_SHOW)
		//							val nid_read = EntityId.from(cursor, COL_NID_READ)
		//							when {
		//								nid_show == null ->
		//									log.w("updateRead[${account_db_id}]: nid_show is null.")
		//								nid_read != null && nid_read >= nid_show ->
		//									log.d("updateRead[${account_db_id}]: nid_read already updated.")
		//
		//								else -> {
		//									log.w("updateRead[${account_db_id}]: update nid_read as ${nid_show}...")
		//									val cv = ContentValues()
		//									nid_show.putTo(cv, COL_NID_READ) //変数名とキー名が異なるのに注意
		//									App1.database.update(table, cv, WHERE_AID, where_args)
		//								}
		//							}
		//						}
		//					}
		//				}
		//			} catch(ex : Throwable) {
		//				log.e(ex, "updateRead[${account_db_id}] failed.")
		//			}
		//		}
		//
		//		fun resetPostAll() {
		//			try {
		//				val cv = ContentValues()
		//				cv.putNull(COL_POST_ID)
		//				cv.put(COL_POST_TIME, 0)
		//				App1.database.update(table, cv, null, null)
		//
		//			} catch(ex : Throwable) {
		//				log.e(ex, "resetPostAll failed.")
		//			}
		//
		//		}
		
		fun resetLastLoad(db_id : Long) {
			try {
				val cv = ContentValues()
				cv.put(COL_LAST_LOAD, 0L)
				App1.database.update(table, cv, WHERE_AID, arrayOf(db_id.toString()))
			} catch(ex : Throwable) {
				log.e(ex, "resetLastLoad(db_id) failed.")
			}
		}
		
		fun resetLastLoad() {
			try {
				val cv = ContentValues()
				cv.put(COL_LAST_LOAD, 0L)
				App1.database.update(table, cv, null, null)
			} catch(ex : Throwable) {
				log.e(ex, "resetLastLoad() failed.")
			}
			
		}
		
		fun getEntityOrderId(account : SavedAccount, src : JSONObject) : EntityId =
			if(account.isMisskey) {
				when(val created_at = src.parseString("createdAt")) {
					null -> EntityId.DEFAULT
					else -> EntityId(TootStatus.parseTime(created_at).toString())
				}
			} else {
				EntityId.mayDefault(src.parseString("id"))
			}
		
		private fun makeNotificationUrl(
			accessInfo : SavedAccount,
			flags : Int,
			since_id : EntityId?
		) = when {
			// MisskeyはsinceIdを指定すると未読範囲の古い方から読んでしまう？
			accessInfo.isMisskey -> "/api/i/notifications"
			
			else -> {
				val sb = StringBuilder(Column.PATH_NOTIFICATIONS) // always contain "?limit=XX"
				
				if(since_id != null) sb.append("&since_id=$since_id")
				
				fun noBit(v : Int, mask : Int) = (v and mask) != mask
				
				if(noBit(flags, 1)) sb.append("&exclude_types[]=reblog")
				if(noBit(flags, 2)) sb.append("&exclude_types[]=favourite")
				if(noBit(flags, 4)) sb.append("&exclude_types[]=follow")
				if(noBit(flags, 8)) sb.append("&exclude_types[]=mention")
				// if(noBit(flags,16)) /* mastodon has no reaction */
				if(noBit(flags, 32)) sb.append("&exclude_types[]=poll")
				
				sb.toString()
			}
		}
		
		fun parseNotificationTime(accessInfo : SavedAccount, src : JSONObject) : Long =
			when {
				accessInfo.isMisskey -> TootStatus.parseTime(src.parseString("createdAt"))
				else -> TootStatus.parseTime(src.parseString("created_at"))
			}
		
		fun parseNotificationType(accessInfo : SavedAccount, src : JSONObject) : String =
			when {
				accessInfo.isMisskey -> src.parseString("type")
				else -> src.parseString("type")
			} ?: "?"
		
		fun deleteCache(dbId : Long) {
			try {
				val cv = ContentValues()
				cv.put(COL_ACCOUNT_DB_ID, dbId)
				cv.put(COL_LAST_LOAD, 0L)
				cv.putNull(COL_DATA)
				App1.database.replaceOrThrow(table, null, cv)
			} catch(ex : Throwable) {
				log.e(ex, "deleteCache failed.")
			}
		}
	}
	
	// load into this object
	fun load() {
		try {
			App1.database.query(
				table,
				null,
				WHERE_AID,
				arrayOf(account_db_id.toString()),
				null,
				null,
				null
			)?.use { cursor ->
				if(cursor.moveToFirst()) {
					this.id = cursor.getLong(COL_ID)
					this.last_load = cursor.getLong(COL_LAST_LOAD)
					this.sinceId = EntityId.from(cursor, COL_SINCE_ID)
					
					val src = cursor.getStringOrNull(COL_DATA)?.toJsonArray()
					if(src != null) {
						for(i in 0 until src.length()) {
							data.add(src.optJSONObject(i) ?: continue)
						}
					}
				} else {
					this.id = - 1
					this.last_load = 0L
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex, "load failed.")
		}
	}
	
	fun save() {
		try {
			val cv = ContentValues()
			cv.put(COL_ACCOUNT_DB_ID, account_db_id)
			cv.put(COL_LAST_LOAD, last_load)
			cv.put(COL_DATA, data.toJsonArray().toString())
			
			val sinceId = sinceId
			if(sinceId == null) {
				cv.putNull(COL_SINCE_ID)
			} else {
				sinceId.putTo(cv, COL_SINCE_ID)
			}
			
			val rv = App1.database.replaceOrThrow(table, null, cv)
			if(rv != - 1L && id == - 1L) id = rv
		} catch(ex : Throwable) {
			log.e(ex, "save failed.")
		}
	}
	
	private fun normalize(account : SavedAccount) {
		
		// 新しい順に並べる
		data.sortWith(Comparator { a, b ->
			val la = a.optLong(KEY_TIME_CREATED_AT)
			val lb = b.optLong(KEY_TIME_CREATED_AT)
			when {
				la < lb -> 1
				la > lb -> - 1
				else -> 0
			}
		})
		
		val typeCount = HashMap<String, Int>()
		val it = data.iterator()
		val duplicateMap = HashSet<EntityId>()
		while(it.hasNext()) {
			val item = it.next()
			
			val id = getEntityOrderId(account, item)
			if(id.isDefault) {
				it.remove()
				continue
			}
			
			if(id.isNewerThan(sinceId)) {
				this.sinceId = id
			}
			
			// skip duplicated
			if(duplicateMap.contains(id)) {
				it.remove()
				continue
			}
			duplicateMap.add(id)
			
			val type = parseNotificationType(account, item)
			
			// 通知しないタイプなら取り除く
			if(! account.canNotificationShowing(type)) {
				it.remove()
				continue
			}
			
			// 種類別に10まで保持する
			val count = 1 + (typeCount[type] ?: 0)
			if(count > 10) {
				it.remove()
				continue
			}
			typeCount[type] = count
		}
		
	}
	
	fun request(
		client : TootApiClient,
		account : SavedAccount,
		flags : Int,
		onError : (TootApiResult) -> Unit,
		isCancelled : () -> Boolean
	) {
		val now = System.currentTimeMillis()
		
		// 前回の更新から一定時刻が経過するまでは処理しない
		val remain = last_load + 120000L - now
		if(remain > 0) {
			log.d("skip request. wait ${remain}ms.")
			return
		}
		
		this.last_load = now
		
		val path = makeNotificationUrl(account, flags, this.sinceId)
		
		try {
			for(nTry in 0 .. 3) {
				
				if(isCancelled()) {
					log.d("cancelled.")
					return
				}
				
				val result = if(account.isMisskey) {
					client.request(path, account.putMisskeyApiToken().toPostRequestBuilder())
				} else {
					client.request(path)
				}
				
				if(result == null) {
					log.d("cancelled.")
					return
				}
				
				val array = result.jsonArray
				if(array != null) {
					account.updateNotificationError(null)

					// データをマージする
					for(i in 0 until array.length()) {
						val item = array.optJSONObject(i) ?: continue
						item.put(KEY_TIME_CREATED_AT, parseNotificationTime(account, item))
						data.add(item)
					}
					
					normalize(account)
					
					return
				}
				
				log.d("request error. ${result.error} ${result.requestInfo}")
				
				account.updateNotificationError("${result.error} ${result.requestInfo}".trim())
				
				onError(result)
				
				// サーバからエラー応答が届いているならリトライしない
				val code = result.response?.code
				if(code != null && code in 200 until 600) {
					break
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex, "request failed.")
		} finally {
			save()
		}
	}
	
	fun inject(account : SavedAccount, list : List<TootNotification>) {
		try {
			val jsonList = list.map { it.json }
			jsonList.forEach { item ->
				item.put(KEY_TIME_CREATED_AT, parseNotificationTime(account, item))
			}
			data.addAll(jsonList)
			normalize(account)
		} catch(ex : Throwable) {
			log.trace(ex, "inject failed.")
		} finally {
			save()
		}
	}
	
	//
	//
	//
	//	fun updatePost(post_id : EntityId, post_time : Long) {
	//		this.post_id = post_id
	//		this.post_time = post_time
	//		try {
	//			val cv = ContentValues()
	//			post_id.putTo(cv, COL_POST_ID)
	//			cv.put(COL_POST_TIME, post_time)
	//			val rows = App1.database.update(table, cv, WHERE_AID, arrayOf(account_db_id.toString()))
	//			log.d(
	//				"updatePost account_db_id=%s,post=%s,%s last_data=%s,update_rows=%s"
	//				, account_db_id
	//				, post_id
	//				, post_time
	//				, last_data?.length
	//				, rows
	//			)
	//
	//		} catch(ex : Throwable) {
	//			log.e(ex, "updatePost failed.")
	//		}
	//
	//	}
	
}
