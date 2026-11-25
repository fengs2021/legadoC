package io.legado.app.help

import io.legado.app.data.entities.Book
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig

/**
 * 「这本书是否记录阅读记录与阅读时长」的唯一判定入口。
 *
 * 三条写入链路（ReadBook / ReadManga / VideoPlay）必须共用这里，禁止各自写条件：
 * - 全局开关 [AppConfig.enableReadRecord] 关闭 → 不记录；
 * - 视频一律不记录（视频不计入阅读时长与阅读记录）；
 * - 书籍详情页逐本关闭（[AppConfig.readRecordExcludeBooks]）→ 该书不记录。
 */
object ReadRecordHelp {

    fun shouldRecord(book: Book?): Boolean {
        if (book == null) return false
        if (!AppConfig.enableReadRecord) return false
        if (book.isVideo) return false
        return !AppConfig.isReadRecordExcluded(book.bookUrl)
    }
}
