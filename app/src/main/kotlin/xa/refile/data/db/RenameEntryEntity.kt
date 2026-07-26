package xa.refile.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 重命名条目实体（计划 §M5 SubTask 5.1.1）。
 *
 * 每条记录对应一次 [xa.refile.core.rename.RenameOperation] 的执行结果，
 * 隶属于某个 [RenameBatchEntity]。撤销时按 [id] 倒序逐条反向 MOVE（targetPath → sourcePath）。
 *
 * [companionsJson] 存 [xa.refile.core.rename.CompanionRename] 列表的 JSON 字符串
 * （由 [xa.refile.data.repository.HistoryRepository] 内的简易编解码器处理），
 * 撤销时反向把每个 companion 的 targetPath 移回 sourcePath。
 *
 * @property id             自增主键（撤销顺序依据）。
 * @property batchId         所属批次 id（外键 + 索引）。
 * @property sourcePath     主文件源路径。
 * @property targetPath     主文件目标路径。
 * @property mediaType      媒体类型字符串（"MOVIE"/"EPISODE"，对应 [xa.refile.core.model.MediaType]）。
 * @property companionsJson 伴随文件列表 JSON（[xa.refile.core.rename.CompanionRename] 数组）。
 * @property status         结果状态字符串（"SUCCESS"/"PARTIAL"/"FAILED"/"SKIPPED"）。
 * @property errorMessage   失败原因（仅 FAILED/PARTIAL 时非空）。
 */
@Entity(
    tableName = "rename_entries",
    foreignKeys = [
        ForeignKey(
            entity = RenameBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("batchId")],
)
data class RenameEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val sourcePath: String,
    val targetPath: String,
    val mediaType: String,
    val companionsJson: String,
    val status: String,
    val errorMessage: String? = null,
)
