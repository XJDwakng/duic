package io.zhudy.duic.repository.impl

import io.zhudy.duic.BizCodeException
import io.zhudy.duic.BizCodes
import io.zhudy.duic.UserContext
import io.zhudy.duic.domain.*
import io.zhudy.duic.repository.AbstractTransactionRepository
import io.zhudy.duic.repository.AppRepository
import org.joda.time.DateTime
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.sql.ResultSet
import java.util.*

/**
 * @author Kevin Zou (kevinz@weghst.com)
 */
class OracleAppRepository(
        transactionManager: PlatformTransactionManager,
        private val jdbcTemplate: NamedParameterJdbcTemplate
) : AppRepository, AbstractTransactionRepository(transactionManager) {

    override fun insert(app: App) = Mono.create<Int> {
        val n = transactionTemplate.execute {
            jdbcTemplate.update(
                    "INSERT INTO DUIC_APP(ID,NAME,PROFILE,DESCRIPTION,TOKEN,IP_LIMIT,CONTENT,V,USERS,CREATED_AT,UPDATED_AT) VALUES(SEQ_DUIC_APP.NEXTVAL,:name,:profile,:description,:token,:ipLimit,:content,:v,:users,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    mapOf(
                            "name" to app.name,
                            "profile" to app.profile,
                            "description" to app.description,
                            "token" to app.token,
                            "ipLimit" to app.ipLimit,
                            "content" to app.content,
                            "v" to app.v,
                            "users" to app.users.joinToString(",")
                    )
            )
        }
        it.success(n)
    }.subscribeOn(Schedulers.elastic())

    override fun delete(app: App, userContext: UserContext) = findOne<App>(app.name, app.profile).flatMap { dbApp ->
        Mono.create<Int> { sink ->
            val n = transactionTemplate.execute {
                val n = jdbcTemplate.update("DELETE FROM DUIC_APP WHERE NAME=:name AND PROFILE=:profile", mapOf(
                        "name" to app.name,
                        "profile" to app.profile
                ))

                insertHistory(dbApp, true, userContext)
                n
            }
            sink.success(n)
        }.subscribeOn(Schedulers.elastic())
    }

    override fun <T> findOne(name: String, profile: String) = Mono.create<App> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query(
                    "SELECT * FROM DUIC_APP WHERE NAME=:name AND PROFILE=:profile",
                    mapOf("name" to name, "profile" to profile),
                    ResultSetExtractor {
                        if (it.next()) {
                            sink.success(mapToApp(it))
                        } else {
                            sink.success()
                        }
                    }
            )
        }
    }.subscribeOn(Schedulers.elastic())

    override fun update(app: App, userContext: UserContext) = findOne<App>(app.name, app.profile).flatMap { dbApp ->
        Mono.create<Int> { sink ->
            val n = transactionTemplate.execute {
                val n = jdbcTemplate.update(
                        "UPDATE DUIC_APP SET TOKEN=:token,IP_LIMIT=:ip_limit,DESCRIPTION=:description,USERS=:users,UPDATED_AT=CURRENT_TIMESTAMP WHERE NAME=:name AND PROFILE=:profile AND V=:v",
                        mapOf(
                                "token" to app.token,
                                "ip_limit" to app.ipLimit,
                                "description" to app.description,
                                "users" to app.users.joinToString(","),
                                "name" to app.name,
                                "profile" to app.profile,
                                "v" to app.v
                        )
                )
                if (n != 1) {
                    if (app.v != dbApp.v) {
                        throw BizCodeException(BizCodes.C_1004)
                    }

                    throw BizCodeException(BizCodes.C_1003, "修改 ${app.name}/${app.profile} 失败")
                }

                insertHistory(dbApp, false, userContext)
                n
            }
            sink.success(n)
        }.subscribeOn(Schedulers.elastic())
    }

    override fun updateContent(app: App, userContext: UserContext) = findOne<App>(app.name, app.profile).flatMap { dbApp ->
        Mono.create<Int> { sink ->
            val v = transactionTemplate.execute {
                val n = jdbcTemplate.update(
                        "UPDATE DUIC_APP SET CONTENT=:content,V=V+1,UPDATED_AT=CURRENT_TIMESTAMP WHERE NAME=:name AND PROFILE=:profile AND V=:v",
                        mapOf(
                                "content" to app.content,
                                "name" to app.name,
                                "profile" to app.profile,
                                "v" to app.v
                        )
                )
                if (n != 1) {
                    if (app.v != dbApp.v) {
                        throw BizCodeException(BizCodes.C_1004)
                    }

                    throw BizCodeException(BizCodes.C_1003, "修改 ${app.name}/${app.profile} 失败")
                }

                insertHistory(dbApp, false, userContext)

                app.v + 1
            }
            sink.success(v)
        }.subscribeOn(Schedulers.elastic())
    }

    override fun findAll() = Flux.create<App> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query("SELECT * FROM DUIC_APP ORDER BY UPDATED_AT") {
                sink.next(mapToApp(it))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findPage(pageable: Pageable) = Mono.zip(
            Flux.create<App> { sink ->
                roTransactionTemplate.execute {
                    jdbcTemplate.query(
                            "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP ) A WHERE ROWNUM <= :e ) WHERE RN >= :b",
                            mapOf("b" to pageable.begin, "e" to pageable.end)
                    ) {
                        sink.next(mapToApp(it))
                    }
                }
                sink.complete()
            }.subscribeOn(Schedulers.elastic()).collectList(),
            Mono.create<Int> {
                val c = roTransactionTemplate.execute {
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(1) FROM DUIC_APP",
                            EmptySqlParameterSource.INSTANCE,
                            Int::class.java
                    )
                }
                it.success(c)
            }.subscribeOn(Schedulers.elastic())
    ).map {
        Page(it.t1, it.t2, pageable)
    }

    override fun findPageByUser(pageable: Pageable, userContext: UserContext) = Mono.zip(
            Flux.create<App> { sink ->
                roTransactionTemplate.execute {
                    jdbcTemplate.query(
                            "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%' ) A WHERE ROWNUM <= :e ) WHERE RN >= :b",
                            mapOf(
                                    "email" to userContext.email,
                                    "b" to pageable.begin,
                                    "e" to pageable.end
                            )
                    ) {
                        sink.next(mapToApp(it))
                    }
                }
                sink.complete()
            }.subscribeOn(Schedulers.elastic()).collectList(),
            Mono.create<Int> {
                val c = roTransactionTemplate.execute {
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(1) FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%'",
                            mapOf("email" to userContext.email),
                            Int::class.java
                    )
                }
                it.success(c)
            }.subscribeOn(Schedulers.elastic())
    ).map {
        Page(it.t1, it.t2, pageable)
    }

    override fun searchPage(q: String, pageable: Pageable) = Mono.zip(
            Flux.create<App> { sink ->
                roTransactionTemplate.execute {
                    val sql = if (q.isEmpty()) {
                        "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP ) A WHERE ROWNUM <= :e ) WHERE RN >= :b"
                    } else {
                        "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP WHERE CONTAINS(CONTENT, :q) > 0 ) A WHERE ROWNUM <= :e ) WHERE RN >= :b"
                    }

                    jdbcTemplate.query(
                            sql,
                            mapOf(
                                    "q" to q,
                                    "b" to pageable.begin,
                                    "e" to pageable.end
                            )
                    ) {
                        sink.next(mapToApp(it))
                    }
                }
                sink.complete()
            }.subscribeOn(Schedulers.elastic()).collectList(),
            Mono.create<Int> {
                val c = roTransactionTemplate.execute {
                    val sql = if (q.isEmpty()) {
                        "SELECT COUNT(*) FROM DUIC_APP"
                    } else {
                        "SELECT COUNT(*) FROM DUIC_APP WHERE CONTAINS(CONTENT, :q) > 0"
                    }
                    jdbcTemplate.queryForObject(sql, mapOf("q" to q), Int::class.java)
                }
                it.success(c)
            }.subscribeOn(Schedulers.elastic())
    ).map {
        Page(it.t1, it.t2, pageable)
    }

    override fun searchPageByUser(q: String, pageable: Pageable, userContext: UserContext) = Mono.zip(
            Flux.create<App> { sink ->
                roTransactionTemplate.execute {
                    val sql = if (q.isEmpty()) {
                        "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%' ) A WHERE ROWNUM <= :e ) WHERE RN >= :b"
                    } else {
                        "SELECT * FROM ( SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%' AND CONTAINS(CONTENT, :q) > 0 ) A WHERE ROWNUM <= :e ) WHERE RN >= :b"
                    }
                    jdbcTemplate.query(
                            sql,
                            mapOf(
                                    "q" to q,
                                    "email" to userContext.email,
                                    "b" to pageable.begin,
                                    "e" to pageable.end
                            )
                    ) {
                        sink.next(mapToApp(it))
                    }
                }
                sink.complete()
            }.subscribeOn(Schedulers.elastic()).collectList(),
            Mono.create<Int> {
                val c = roTransactionTemplate.execute {
                    val sql = if (q.isEmpty()) {
                        "SELECT COUNT(*) FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%'"
                    } else {
                        "SELECT COUNT(*) FROM DUIC_APP WHERE USERS LIKE '%' || :email || '%' AND CONTAINS(CONTENT, :q) > 0"
                    }
                    jdbcTemplate.queryForObject(sql, mapOf("q" to q, "email" to userContext.email), Int::class.java)
                }
                it.success(c)
            }.subscribeOn(Schedulers.elastic())
    ).map {
        Page(it.t1, it.t2, pageable)
    }

    override fun findByUpdatedAt(updateAt: Date) = Flux.create<App> { sink ->
        transactionTemplate.execute {
            jdbcTemplate.query(
                    "SELECT * FROM DUIC_APP WHERE UPDATED_AT > :updated_at ORDER BY UPDATED_AT",
                    mapOf("updated_at" to updateAt)
            ) {
                sink.next(mapToApp(it))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findLast50History(name: String, profile: String) = Flux.create<AppContentHistory> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query(
                    "SELECT A.*, ROWNUM RN FROM ( SELECT * FROM DUIC_APP_HISTORY WHERE NAME=:name AND PROFILE=:profile ORDER BY CREATED_AT DESC ) A WHERE ROWNUM <= 50",
                    mapOf("name" to name, "profile" to profile)
            ) {
                sink.next(AppContentHistory(
                        hid = it.getString("id"),
                        updatedBy = it.getString("updated_by") ?: "",
                        content = it.getString("content") ?: "",
                        updatedAt = it.getTimestamp("created_at")
                ))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findAllNames() = Flux.create<String> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query("SELECT NAME FROM DUIC_APP") {
                sink.next(it.getString("name"))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findProfilesByName(name: String) = Flux.create<String> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query("SELECT PROFILE FROM DUIC_APP WHERE NAME=:name", mapOf("name" to name)) {
                sink.next(it.getString("profile"))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findDeletedByCreatedAt(createdAt: Date) = Flux.create<AppHistory> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query(
                    "SELECT * FROM DUIC_APP_HISTORY WHERE CREATED_AT > :created_at AND DELETED_BY IS NOT NULL ORDER BY CREATED_AT",
                    mapOf("created_at" to createdAt)
            ) {
                sink.next(AppHistory(
                        id = it.getString("id"),
                        name = it.getString("name"),
                        profile = it.getString("profile"),
                        description = it.getString("description"),
                        content = it.getString("content") ?: "",
                        token = it.getString("token") ?: "",
                        ipLimit = it.getString("ip_limit") ?: "",
                        v = it.getInt("v"),
                        createdAt = DateTime(it.getTimestamp("created_at")),
                        updatedBy = it.getString("updated_by") ?: "",
                        deletedBy = it.getString("deleted_by") ?: "",
                        users = it.getString("users").split(",")
                ))
            }
        }
        sink.complete()
    }.subscribeOn(Schedulers.elastic())

    override fun findLastDataTime() = Mono.create<Long> { sink ->
        roTransactionTemplate.execute {
            jdbcTemplate.query("SELECT UPDATED_AT FROM DUIC_APP ORDER BY UPDATED_AT DESC", ResultSetExtractor {
                if (it.next()) {
                    sink.success(it.getTimestamp("updated_at").time)
                } else {
                    sink.success(0)
                }
            })
        }
    }

    private fun insertHistory(app: App, delete: Boolean, userContext: UserContext) = jdbcTemplate.update(
            """INSERT INTO DUIC_APP_HISTORY(ID,NAME,PROFILE,DESCRIPTION,TOKEN,IP_LIMIT,V,CONTENT,USERS,DELETED_BY,UPDATED_BY,CREATED_AT)
VALUES(SEQ_DUIC_APP_HISTORY.NEXTVAL,:name,:profile,:description,:token,:ip_limit,:v,:content,:users,:deleted_by,:updated_by,CURRENT_TIMESTAMP)""",
            mapOf(
                    "name" to app.name,
                    "profile" to app.profile,
                    "description" to app.description,
                    "token" to app.token,
                    "ip_limit" to app.ipLimit,
                    "v" to app.v,
                    "content" to app.content,
                    "users" to app.users.joinToString(","),
                    "deleted_by" to if (delete) userContext.email else "",
                    "updated_by" to if (!delete) userContext.email else ""
            )
    )

    private fun mapToApp(rs: ResultSet) = App(
            id = rs.getString("id"),
            name = rs.getString("name"),
            profile = rs.getString("profile"),
            description = rs.getString("description"),
            token = rs.getString("token") ?: "",
            ipLimit = rs.getString("ip_limit") ?: "",
            v = rs.getInt("v"),
            createdAt = DateTime(rs.getTimestamp("created_at")),
            updatedAt = DateTime(rs.getTimestamp("updated_at")),
            content = rs.getString("content") ?: "",
            users = rs.getString("users").split(",")
    )

}