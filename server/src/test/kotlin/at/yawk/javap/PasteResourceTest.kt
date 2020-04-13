/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.yawk.javap

import at.yawk.javap.model.Paste
import at.yawk.javap.model.PasteDao
import at.yawk.javap.model.PasteDto
import at.yawk.javap.model.ProcessingInput
import at.yawk.javap.model.ProcessingOutput
import com.fasterxml.jackson.databind.ObjectMapper
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcConnectionPool
import org.jdbi.v3.core.Jdbi
import org.testng.Assert
import org.testng.annotations.AfterTest
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.lang.Exception
import javax.sql.DataSource
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotAuthorizedException
import javax.ws.rs.NotFoundException

/**
 * @author yawkat
 */
class PasteResourceTest {
    private val processor = object : Processor {
        override fun process(input: ProcessingInput): ProcessingOutput {
            return ProcessingOutput("compiler log " + input.code, "javap " + input.code, "procyon " + input.code)
        }
    }
    private val dataSource: DataSource = JdbcConnectionPool.create("jdbc:h2:mem:test", "", "")
    private val dbi = Jdbi.create(dataSource).installPlugins()
    private val defaultPaste = DefaultPaste(processor)
    private val pasteResource: PasteResource = PasteResource(dbi.onDemand(PasteDao::class.java),
            processor,
            defaultPaste)

    @BeforeClass
    fun setupDb() {
        val flyway = Flyway()
        flyway.dataSource = dataSource
        flyway.migrate()
    }

    @AfterTest
    fun clearDb() {
        dbi.useHandle<Exception> { it.createUpdate("DELETE FROM paste").execute() }
    }

    @Test
    fun `create get update cycle`() {
        val token = "abcdef"
        val input1 = ProcessingInput("test code 1", Sdks.defaultJava.name)
        val input2 = ProcessingInput("test code 2", Sdks.defaultJava.name)

        val created = pasteResource.createPaste(token, PasteDto.Create(input1))
        Assert.assertEquals(created, PasteDto(created.id, true, input1, processor.process(input1)))

        Assert.assertEquals(pasteResource.getPaste(token, created.id), created)

        val updated = pasteResource.updatePaste(token, created.id, PasteDto.Update(input2))
        Assert.assertEquals(updated, created.copy(input = input2, output = processor.process(input2)))

        Assert.assertEquals(pasteResource.getPaste(token, created.id), updated)
    }

    @Test(expectedExceptions = [NotFoundException::class])
    fun `paste get not found`() {
        pasteResource.getPaste(null, "xyz")
    }

    @Test(expectedExceptions = [NotFoundException::class])
    fun `paste update not found`() {
        pasteResource.updatePaste("abcdef", "xyz", PasteDto.Update())
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste create invalid user token`() {
        pasteResource.createPaste("#", PasteDto.Create(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste create no user token`() {
        pasteResource.createPaste(null, PasteDto.Create(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste create empty user token`() {
        pasteResource.createPaste("", PasteDto.Create(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste update invalid user token`() {
        pasteResource.updatePaste("#", "xyz", PasteDto.Update(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste update no user token`() {
        pasteResource.updatePaste(null, "xyz", PasteDto.Update(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [BadRequestException::class])
    fun `paste update empty user token`() {
        pasteResource.updatePaste("", "xyz", PasteDto.Update(ProcessingInput("abc", Sdks.defaultJava.name)))
    }

    @Test(expectedExceptions = [NotAuthorizedException::class])
    fun `deny paste update for other user`() {
        val created = pasteResource.createPaste("abc",
                PasteDto.Create(ProcessingInput("abc", Sdks.defaultJava.name)))
        pasteResource.updatePaste("def",
                created.id,
                PasteDto.Update(ProcessingInput("def", Sdks.defaultJava.name)))
    }

    @Test
    fun `paste dto serialization`() {
        val objectMapper = ObjectMapper().findAndRegisterModules()
        val input = ProcessingInput("in", Sdks.defaultJava.name)
        Assert.assertEquals(
                objectMapper.writeValueAsString(PasteDto("a", false, input, processor.process(input))),
                """{"id":"a","editable":false,"input":{"code":"in","compilerName":"${Sdks.defaultJava.name}"},"output":{"compilerLog":"compiler log in","javap":"javap in","procyon":"procyon in"}}"""
        )
    }

    @Test
    fun `get default paste`() {
        Assert.assertEquals(
                pasteResource.getPaste(null, "default:JAVA").input,
                defaultPaste.defaultPastes[0].input
        )
        Assert.assertEquals(
                pasteResource.getPaste(null, "default:JAVA").output,
                defaultPaste.defaultPastes[0].output
        )
    }
}