package com.teamshare.app.domain.usecase.document

import com.teamshare.app.domain.model.SharedDocument
import com.teamshare.app.domain.repository.IDocumentRepository
import kotlinx.coroutines.flow.Flow

/**
 * UC-08: Xem danh mục tài liệu nhóm & Trạng thái tải về (SRS FR-DOC-02).
 * Đọc luồng danh mục tài liệu thuộc không gian nhóm từ CSDL.
 */
class GetDocumentCatalogUseCase(
    private val documentRepository: IDocumentRepository
) {
    operator fun invoke(teamId: String): Flow<List<SharedDocument>> {
        return documentRepository.getDocumentsByTeam(teamId)
    }
}

