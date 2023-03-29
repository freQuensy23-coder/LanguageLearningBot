import dev.inmo.tgbotapi.extensions.api.send.reply
import dev.inmo.tgbotapi.extensions.api.send.send
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitAnyContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.expectations.waitDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.telegramBotWithBehaviourAndFSMAndStartLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.command
import dev.inmo.tgbotapi.extensions.utils.extensions.parseCommandsWithParams
import dev.inmo.tgbotapi.extensions.utils.extensions.sameThread
import dev.inmo.tgbotapi.extensions.utils.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import reverso.LanguageCode
import database.Users
import dev.inmo.tgbotapi.extensions.utils.usernameChatOrNull
import org.ktorm.database.Database
import org.ktorm.support.postgresql.PostgreSqlDialect
import org.ktorm.support.postgresql.insertOrUpdate
import java.lang.System.getenv

fun detectLanguage(text: String): LanguageCode {
    // Detect text language. Returns language code (en, ru, fr etc.). Use symbol search
    return if (text.contains(Regex("[а-яА-Я]"))) {
        LanguageCode("ru")
    }
    else {
        LanguageCode("en")
    }
}



suspend fun main(args: Array<String>) {
    val translator = ReversoTranslatorAPI()
    // bot token = getenv("BOT_TOKEN") or args.first() if None
    val botToken = getenv("BOT_TOKEN") ?: args.first()
    val database = Database.connect("jdbc:postgresql://${getenv("DATABASE_IP")}:${getenv("DATABASE_PORT")}/${getenv("DATABASE_NAME")}",  user = getenv("DATABASE_USER"), password = getenv("DATABASE_PASSWORD"),  dialect = PostgreSqlDialect())
    telegramBotWithBehaviourAndFSMAndStartLongPolling<BotState>(
        botToken,
        CoroutineScope(Dispatchers.IO),
        onStateHandlingErrorHandler = { state, e ->
            when (state) {
                is ExpectTranslationRequest -> {
                    println("Thrown error on ExpectContentOrStopState")
                }
                is StopState -> {
                    println("Thrown error on StopState")
                }
                else -> { println("thrown error inside of a void")}
            }
            e.printStackTrace()
            state
        }
    )//sets up the bot, now the behaviour builder:
    {
        strictlyOn<MainMenu>{
            val mm = MessagesManager(it.sourceMessage.chat)
            val keyboard2 = InlineKeyboardMarkup(CallbackDataInlineKeyboardButton(text = "Translate!", callbackData = "GoToTranslation"))
            sendMessage(it.context, text=mm.getMainMenuMessage(), replyMarkup =  keyboard2 )
            val callback = waitDataCallbackQuery().first()
            val content = callback.data
            println(content)
            when(content){
                "GoToTranslation" -> {
                    ExpectTranslationRequest(it.context, it.sourceMessage)
                }
                else -> {
                    println("no")
                    it
                }
            }
        }

        strictlyOn<ExpectTranslationRequest> {
            val mm = MessagesManager(it.sourceMessage.chat)
            send(
                it.context,
            ) {
                +mm.getTranslationRequestMessage()
            }
            val contentMessage = waitAnyContentMessage().filter { message ->
                message.sameThread(it.sourceMessage)
            }.first()
            val content = contentMessage.content

            when {
                content is TextContent && content.parseCommandsWithParams().keys.contains("stop") -> StopState(it.context, it.sourceMessage)
                content is TextContent -> {
                    if (content.text.length > 100) {
                        reply(contentMessage, mm.getTextToLongMessage())
                    }
                    try {
                        val language = detectLanguage(content.text)
                        val targetLang = if (language.code == "en") LanguageCode("ru") else LanguageCode("en")
                        val translated = translator.translate(content.text, language, targetLang)
                        reply(contentMessage, mm.getTranslationResultMessage(from=language, to=targetLang, translation=translated), parseMode = HTMLParseMode)
                    } catch (e: Exception) {
                        reply(contentMessage, mm.getTranslationErrorMessage())
                        print(e)
                    }
                    it
                }
                else -> {
                    reply(contentMessage, mm.getSomeErrorMessage())
                    StopState(it.context, it.sourceMessage)
                }
            }
        }

        strictlyOn<StopState> {
            MainMenu(it.context, it.sourceMessage)
        }

        command("start") {message ->
            val mm = MessagesManager(message.chat)
            send(message.chat, mm.getMainMenuMessage())

            // insert user to database if not exists (with same chat_id)
            database.insertOrUpdate(Users) {
                set(it.chat_id, message.chat.id.chatId.toString())
                set(it.name, message.chat.usernameChatOrNull()?.username?.usernameWithoutAt)// If someone changes username it can not work!
            }


        }

    }.second.join()
}
