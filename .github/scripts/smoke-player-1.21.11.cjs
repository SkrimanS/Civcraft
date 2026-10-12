// Isolated CI player for an offline Paper 1.21.11 test server.
const mineflayer = require('mineflayer')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

async function runPlayerTest () {
  return new Promise((resolve, reject) => {
    let finished = false
    const messages = []
    let bot
    const deadline = setTimeout(() => fail(new Error('Player login or gameplay test timed out')), 60000)

    function fail (error) {
      if (finished) return
      finished = true
      clearTimeout(deadline)
      if (bot) bot.end()
      reject(error)
    }

    try {
      bot = mineflayer.createBot({
        host: '127.0.0.1',
        port: 25565,
        username: 'CivSmokeBot',
        version: '1.21.11',
        auth: 'offline'
      })
    } catch (error) {
      fail(error)
      return
    }

    bot.on('kicked', reason => fail(new Error('Bot kicked: ' + JSON.stringify(reason))))
    bot.on('error', fail)
    bot.on('end', reason => {
      if (!finished) fail(new Error('Player connection closed: ' + reason))
    })
    bot.on('messagestr', message => {
      messages.push(message)
      console.log('[CivSmokeBot] ' + message)
    })

    bot.once('spawn', () => {
      ;(async () => {
        console.log('Player successfully joined Paper 1.21.11')
        for (const cmd of ['/civ create CivSmoke', '/town create TownSmoke', '/town claim', '/town claim', '/civ create CivAgain', '/research list']) {
          await delay(2200)
          console.log('Executing as player: ' + cmd)
          bot.chat(cmd)
        }
        await delay(2200)
        if (!messages.some(message => message.includes('Этот чанк уже приватизирован.'))) {
          throw new Error('Expected friendly duplicate-claim message from CivCraft')
        }
        finished = true
        clearTimeout(deadline)
        bot.quit()
        resolve()
      })().catch(fail)
    })
  })
}

runPlayerTest().catch(error => {
  console.error(error.stack || error.message || String(error))
  process.exitCode = 1
})
