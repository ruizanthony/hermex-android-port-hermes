package com.uzairansar.hermex.data.repository

import com.uzairansar.hermex.core.model.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlin.math.abs

/** Hidden nodes are proof only. Mirrors the server's legacy null-profile = default contract. */
internal class SparseLineageResolver {
    private data class Proof(val child: SessionSummary?, val checkedAt: Long)
    private val proofs=mutableMapOf<Pair<String,String>,Proof>()
    private val mutex=Mutex()
    private fun profile(row:SessionSummary)=row.profile?.takeIf { it.isNotBlank() } ?: "default"
    private fun eligible(row:SessionSummary)=!row.isListSubagent &&
        !row.sessionSource.equals("fork",true) && row.relationshipType?.lowercase() !in setOf("fork","branch","subagent") &&
        row.rawSource in setOf("desktop","webui")
    suspend fun enrich(rows:List<SessionSummary>,metadata:suspend(String)->SessionSummary?,report:suspend(String)->CompressionReport):List<SessionSummary> {
        if(!mutex.tryLock()) return rows
        try {
            val now=System.currentTimeMillis()
            proofs.entries.removeAll { now-it.value.checkedAt>60_000 }
            val byId=rows.associateBy { it.sessionId }
            // Mutable archive/stream state is read afresh each pass, not from link cache.
            val observed=mutableMapOf<String?,SessionSummary?>().apply { putAll(byId) }
            suspend fun fresh(id:String):SessionSummary? {
                if (!observed.containsKey(id)) observed[id]=metadata(id)
                return observed[id]
            }
            suspend fun childOf(parent:SessionSummary):SessionSummary? {
                val id=parent.sessionId ?: return null
                if(!eligible(parent)) return null
                val key=profile(parent) to id
                proofs[key]?.let { proof ->
                    val cached=proof.child ?: return null
                    val child=fresh(cached.sessionId!!) ?: return null
                    if (!eligible(child) || profile(child)!=profile(parent) ||
                        (child.parentSessionId!=null && child.parentSessionId!=id)) return null
                    return child.copy(parentSessionId=id,profile=profile(child))
                }
                val r=report(id)
                val ended=r.segments.singleOrNull()
                val end=ended?.updatedAt
                if(r.found!=true || r.sessionId!=id || r.manualReview!=false || r.totalSegments!=1 ||
                    ended?.sessionId!=id || ended.endReason!="compression" || ended.active!=false ||
                    end==null || !end.isFinite() || end<=0) { proofs[key]=Proof(null,now);return null }
                val candidate=r.children.filter { c -> c.startedAt?.let { it.isFinite() && it>0 && abs(it-end)<=5 }==true }.singleOrNull()
                val nextId=candidate?.sessionId
                if(nextId==null || candidate.role!="child_session" || nextId==id) { proofs[key]=Proof(null,now);return null }
                val child=fresh(nextId)
                if(child?.sessionId!=nextId || !eligible(child) || profile(child)!=profile(parent) ||
                    (child.parentSessionId!=null && child.parentSessionId!=id)) { proofs[key]=Proof(null,now);return null }
                // The report attests direct parentage when legacy metadata omits it.
                val normalizedParent=parent.copy(profile=profile(parent))
                val normalizedChild=child.copy(parentSessionId=id,profile=profile(child))
                val confirmed=confirmCompressionReport(normalizedParent,listOf(normalizedParent,normalizedChild),r)
                val result=normalizedChild.takeIf { confirmed.continuationSessionId==nextId }
                proofs[key]=Proof(result,now)
                return result
            }
            withTimeoutOrNull(6_000) {
                for(row in rows.sortedBy { proofs[profile(it) to it.sessionId]?.checkedAt ?: Long.MIN_VALUE }) {
                    var current=row
                    val seen=mutableSetOf<String>()
                    try {
                        while(seen.size<128) {
                            val id=current.sessionId ?: break
                            if(!seen.add(id)) break
                            current=childOf(current) ?: break
                        }
                    } catch(e:CancellationException) { throw e }
                    catch(_:Exception) { current.sessionId?.let { proofs[profile(current) to it]=Proof(null,now) } }
                }
            }
            return rows.map { row ->
                var current=row
                val seen=mutableSetOf<String>()
                while(eligible(current) && current.sessionId!=null && seen.add(current.sessionId!!)) {
                    val next=proofs[profile(current) to current.sessionId!!]?.child ?: break
                    val fresh=observed[next.sessionId] ?: break
                    if(!eligible(fresh) || profile(fresh)!=profile(row) ||
                        (fresh.parentSessionId!=null && fresh.parentSessionId!=current.sessionId)) break
                    current=fresh.copy(parentSessionId=current.sessionId,profile=profile(fresh))
                }
                val reached=proofs[profile(current) to current.sessionId]
                val linked=current.sessionId!=row.sessionId && reached!=null && reached.child==null
                if(linked) row.copy(lineageRootId=current.sessionId,
                    compressionTipArchived=current.archived,
                    compressionArchiveCheckedAt=now)
                else row.copy(compressionTipArchived=null,compressionArchiveCheckedAt=null)
            }
        } finally { mutex.unlock() }
    }
}
