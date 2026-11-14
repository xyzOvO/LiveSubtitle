package com.wo.livesub.core

import java.text.Normalizer

/**
 * 单词 / 固定短语的离线词表兜底。
 *
 * 为什么需要它：ja→zh 的 v4 模型是在 ASMR 台本上微调的，训练语料全是**完整句子**，
 * 没有「孤立单词」这种输入形态。把单词喂进去，decoder 在 BOS 之后的首个 token 会落在
 * 一批高频寒暄词之间（实测 top1/top2 的 logits 差不到 0.5），argmax 随手挑一个就立刻收尾：
 *
 *   にほんご            → 诶......?     （fp16 图更差：喂,喂,喂,）
 *   日本語             → 嗯......
 *   こんにちは          → 哈喽...      （fp16 图正常：你好,）
 *
 * 而同一模型的整句翻译完全正常（日本語を勉強しています → 我在学日语呢）。
 * 所以「单词」这条路不该交给模型——它不是能力不足，是压根没学过这个任务。
 *
 * 收词原则（三条同时满足才收）：
 *  1. 短：整串匹配，超过 [MAX_CHARS] 直接放行，长句永远走模型；
 *  2. 模型大概率翻不对：孤立词、黑话、片头固定文本——ASMR 台本里不会出现这些形态；
 *  3. 在本应用的场景里高频：**日语直播 / 视频字幕**（配信黑话、片头警示、弹幕反应词），
 *     而不是通用词典。这也是本表与普通辞书的区别。
 *
 * 匹配规则：**只认整串**。去首尾空白与标点、NFKC 归一后与表项全等才命中，
 * 命中直接返回译文、不加载任何推理；未命中原样交给模型，行为与接入前完全一致。
 * 因此这张表只会改善「表里有的词」，不会引入新的失败模式。
 */
internal object ShortGlossary {

    /**
     * 剥标点后允许的最大长度。最长表项是「よろしくお願いします」「ありがとうございます」（各 10 字），留 6 字余量。
     */
    private const val MAX_CHARS = 16

    /**
     * 剥标点**之前**的宽松上限，只用来挡明显超长的句子，避免为一句整句做无用功。
     * 必须显著大于 [MAX_CHARS]，否则「よろしくお願いします！！」这类带标点的合法词
     * 会在剥标点之前就被误杀。
     */
    private const val HARD_LIMIT = 64

    /** 首尾可以剥掉的标点；刻意的「ー」不在其中，避免破坏「コーヒー」这类词。 */
    private const val EDGE_PUNCT = "。、，．！？!?,…‥「」『』（）()【】[]{}<>~～·・:：;；\"'“”‘’"

    /**
     * @param lang 源语言；目前有 ja / en 两张表，其余语言一律返回 null。
     * @return 命中返回译文，否则 null。
     */
    fun lookup(raw: String, lang: String): String? = when (lang) {
        "ja" -> normalize(raw)?.let { ENTRIES[it] }
        "en" -> normalizeEn(raw)?.let { ENTRIES_EN[it] }
        else -> null
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > HARD_LIMIT) return null
        val s = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        var start = 0
        var end = s.length
        while (start < end && isEdge(s[start])) start++
        while (end > start && isEdge(s[end - 1])) end--
        if (start >= end) return null
        val core = s.substring(start, end)
        return if (core.length > MAX_CHARS) null else core
    }

    private fun isEdge(c: Char): Boolean = c.isWhitespace() || EDGE_PUNCT.indexOf(c) >= 0

    /**
     * 英文上限按**字符**单独放宽到 [EN_MAX_CHARS]：直播固定句
     * 「don't forget to subscribe」就有 25 个字符，用日语的 16 放不进去。
     */
    private const val EN_MAX_CHARS = 40

    private val WHITESPACE = Regex("\\s+")

    /**
     * 英文归一：剥首尾标点 → 小写 → 压空格。
     *
     * 小写是必须的：`lowercase()` 走 invariant locale，`Thanks` / `THANKS` / `thanks`
     * 都能落到同一个 key，而 SPM 分词是大小写敏感的，同一句会切出不同 piece。
     */
    private fun normalizeEn(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > HARD_LIMIT) return null
        val s = Normalizer.normalize(trimmed, Normalizer.Form.NFKC)
        var start = 0
        var end = s.length
        while (start < end && isEdge(s[start])) start++
        while (end > start && isEdge(s[end - 1])) end--
        if (start >= end) return null
        val core = s.substring(start, end).lowercase().replace(WHITESPACE, " ")
        return if (core.length > EN_MAX_CHARS) null else core
    }

    /**
     * 词表。同一词条的平假名 / 汉字 / 片假名几种写法都收，因为模型的分词对写法敏感
     * （「にほんご」切 3 个 piece 崩、「日本語」切 1 个 piece 也崩），但用户打哪种都该有答案。
     *
     * 敬语层级**有意不区分**：「おはよう」与「おはようございます」同译「早上好」。
     * 字幕场景下观众要的是意思，不是敬语等级；区分反而会让同一句话在不同片段里译法跳变。
     * 唯一例外是「ありがとう」/「ありがとうございます」，后者译「谢谢您」成本为零，保留差别。
     */
    private val ENTRIES: Map<String, String> = mapOf(

        // ── 一、配信 / 直播黑话：模型训练集里完全没有这类词，最容易翻成噪声 ──
        "配信" to "直播",
        "配信中" to "直播中",
        "生放送" to "直播",
        "生配信" to "直播",
        "枠" to "场次",
        "雑談" to "闲聊",
        "歌枠" to "歌回",
        "凸待ち" to "连麦等待",
        "凸" to "连麦",
        "コラボ" to "联动",
        "コメント" to "评论",
        "チャンネル" to "频道",
        "チャンネル登録" to "订阅频道",
        "登録" to "订阅",
        "高評価" to "点赞",
        "低評価" to "踩",
        "フォロー" to "关注",
        "通知" to "通知",
        "スパチャ" to "打赏",
        "スーパーチャット" to "打赏",
        "メンバー" to "会员",
        "メンバー限定" to "会员限定",
        "メン限" to "会员限定",
        "視聴者" to "观众",
        "リスナー" to "听众",
        "アーカイブ" to "回放",
        "切り抜き" to "切片",
        "サムネ" to "封面",
        "概要欄" to "简介栏",
        "初見" to "初次见面",
        "初見さん" to "新观众",
        "常連" to "老观众",
        "荒らし" to "捣乱的",
        "通報" to "举报",
        "ミュート" to "静音",
        "BGM" to "背景音乐",
        "待機所" to "待机室",

        // ── 二、视频片头 / 片尾的固定文本：整块出现在画面上，模型常译得不成句 ──
        "注意" to "注意",
        "警告" to "警告",
        "危険" to "危险",
        "禁止" to "禁止",
        "無断転載" to "禁止转载",
        "転載禁止" to "禁止转载",
        "著作権" to "著作权",
        "ネタバレ" to "剧透",
        "閲覧注意" to "谨慎观看",
        "グロ注意" to "血腥警告",
        "音量注意" to "注意音量",
        "イヤホン推奨" to "建议使用耳机",
        "ヘッドホン推奨" to "建议使用耳机",
        "本編" to "正片",
        "前編" to "前篇",
        "後編" to "后篇",
        "前回" to "上次",
        "次回" to "下次",
        "予告" to "预告",
        "概要" to "概要",
        "詳細" to "详情",
        "出典" to "出处",
        "素材" to "素材",

        // ── 三、反应词 / 口头禅：ASR 在呼吸、笑声、短促语气上吐出来的碎词，模型只会给「嗯……」 ──
        "えー" to "诶——",
        "えっ" to "诶？",
        "ええ" to "嗯",
        "あっ" to "啊",
        "おお" to "哦",
        "うわ" to "哇",
        "へえ" to "诶",
        "ふーん" to "嗯",
        "うん" to "嗯",
        "うんうん" to "嗯嗯",
        "はい" to "嗯",
        "はいはい" to "好好",
        "いいえ" to "不是",
        "そう" to "是啊",
        "そうそう" to "对对",
        "そうか" to "这样啊",
        "そうですね" to "是啊",
        "そうです" to "是的",
        "なるほど" to "原来如此",
        "たしかに" to "确实",
        "まじ" to "真的",
        "マジ" to "真的",
        "ほんと" to "真的",
        "本当" to "真的",
        "やばい" to "糟了",
        "すごい" to "厉害",
        "やった" to "太好了",
        "よかった" to "太好了",
        "よし" to "好",
        "だめ" to "不行",
        "やめて" to "别",
        "ちょっと" to "等一下",
        "ちょっと待って" to "等一下",
        "待って" to "等等",
        "あの" to "那个……",
        "あのね" to "那个啊……",
        "あのー" to "那个……",
        "あのう" to "那个……",
        "えっと" to "嗯……",
        "えーと" to "嗯……",
        "えーっと" to "嗯……",
        "うーん" to "嗯……",
        "めっちゃ" to "超级",
        "すごく" to "非常",
        "とても" to "非常",
        "ちゃんと" to "好好地",
        "やっぱり" to "果然",
        "たぶん" to "大概",
        "きっと" to "一定",
        "もう" to "已经",
        "まだ" to "还",
        "すぐ" to "马上",
        "あとで" to "待会儿",

        // ── 四、寒暄 / 日常：短、高频，且有固定译法，交给模型容易漂 ──
        "おはよう" to "早上好",
        "おはようございます" to "早上好",
        "こんにちは" to "你好",
        "こんにちわ" to "你好",
        "こんばんは" to "晚上好",
        "おやすみ" to "晚安",
        "おやすみなさい" to "晚安",
        "ありがとう" to "谢谢",
        "ありがとうございます" to "谢谢您",
        "どうも" to "谢谢",
        "すみません" to "不好意思",
        "ごめん" to "抱歉",
        "ごめんなさい" to "对不起",
        "はじめまして" to "初次见面",
        "よろしく" to "请多关照",
        "よろしくお願いします" to "请多关照",
        "お願いします" to "拜托了",
        "いただきます" to "我开动了",
        "ごちそうさま" to "我吃饱了",
        "ごちそうさまでした" to "我吃饱了",
        "おかえり" to "欢迎回来",
        "おかえりなさい" to "欢迎回来",
        "いってきます" to "我出门了",
        "いってらっしゃい" to "路上小心",
        "ただいま" to "我回来了",
        "おつかれ" to "辛苦了",
        "おつかれさま" to "辛苦了",
        "お疲れ様" to "辛苦了",
        "お疲れ様でした" to "辛苦了",
        "おめでとう" to "恭喜",
        "がんばって" to "加油",
        "頑張って" to "加油",
        "だいじょうぶ" to "没问题",
        "大丈夫" to "没问题",
        "さようなら" to "再见",
        "またね" to "再见",
        "おひさしぶり" to "好久不见",
        "お久しぶり" to "好久不见",
        "ようこそ" to "欢迎",
        "いらっしゃい" to "欢迎光临",
        "いらっしゃいませ" to "欢迎光临",

        // ── 五、指示词 / 疑问词 / 人称：单字极短，模型完全没有上下文可依 ──
        "これ" to "这个",
        "それ" to "那个",
        "あれ" to "那个",
        "ここ" to "这里",
        "そこ" to "那里",
        "あそこ" to "那里",
        "こちら" to "这边",
        "そちら" to "那边",
        "どれ" to "哪个",
        "どこ" to "哪里",
        "なに" to "什么",
        "何" to "什么",
        "だれ" to "谁",
        "誰" to "谁",
        "いつ" to "什么时候",
        "なぜ" to "为什么",
        "どうして" to "为什么",
        "どう" to "怎么",
        "いくら" to "多少钱",
        "みんな" to "大家",
        "みなさん" to "各位",
        "皆さん" to "各位",
        "わたし" to "我",
        "私" to "我",
        "ぼく" to "我",
        "僕" to "我",
        "おれ" to "我",
        "俺" to "我",
        "あなた" to "你",
        "きみ" to "你",
        "君" to "你",

        // ── 六、名词：视频里被反复提及的对象，单词形态下模型照样会崩 ──
        "にほんご" to "日语",
        "日本語" to "日语",
        "にほん" to "日本",
        "日本" to "日本",
        "ちゅうごくご" to "中文",
        "中国語" to "中文",
        "えいご" to "英语",
        "英語" to "英语",
        "がっこう" to "学校",
        "学校" to "学校",
        "せんせい" to "老师",
        "先生" to "老师",
        "ともだち" to "朋友",
        "友達" to "朋友",
        "かぞく" to "家人",
        "家族" to "家人",
        "しごと" to "工作",
        "仕事" to "工作",
        "じかん" to "时间",
        "時間" to "时间",
        "おかね" to "钱",
        "お金" to "钱",
        "みず" to "水",
        "水" to "水",
        "ごはん" to "米饭",
        "ご飯" to "米饭",
        "いえ" to "家",
        "家" to "家",
        "へや" to "房间",
        "部屋" to "房间",
        "こえ" to "声音",
        "声" to "声音",
        "うた" to "歌",
        "歌" to "歌",
        "おんがく" to "音乐",
        "音楽" to "音乐",
        "ゲーム" to "游戏",
        "アニメ" to "动画",
        "まんが" to "漫画",
        "漫画" to "漫画",
        "えいが" to "电影",
        "映画" to "电影",
        "ばんぐみ" to "节目",
        "番組" to "节目",
        "せいゆう" to "声优",
        "声優" to "声优",

        // ── 七、动词 / 形容词 ──
        "たべる" to "吃",
        "食べる" to "吃",
        "のむ" to "喝",
        "飲む" to "喝",
        "いく" to "去",
        "行く" to "去",
        "くる" to "来",
        "来る" to "来",
        "みる" to "看",
        "見る" to "看",
        "きく" to "听",
        "聞く" to "听",
        "はなす" to "说",
        "話す" to "说",
        "よむ" to "读",
        "読む" to "读",
        "かく" to "写",
        "書く" to "写",
        "ねる" to "睡",
        "寝る" to "睡",
        "おきる" to "起床",
        "起きる" to "起床",
        "わかる" to "明白",
        "分かる" to "明白",
        "しらない" to "不知道",
        "知らない" to "不知道",
        "できる" to "会",
        "すき" to "喜欢",
        "好き" to "喜欢",
        "きらい" to "讨厌",
        "嫌い" to "讨厌",
        "かわいい" to "可爱",
        "きれい" to "漂亮",
        "こわい" to "害怕",
        "うれしい" to "高兴",
        "たのしい" to "开心",
        "楽しい" to "开心",
        "つかれた" to "累了",
        "疲れた" to "累了",
        "むずかしい" to "难",
        "難しい" to "难",
        "かんたん" to "简单",
        "簡単" to "简单",

        // ── 八、时间 / 频度：直播里报进度、约时间离不开 ──
        "きょう" to "今天",
        "今日" to "今天",
        "あした" to "明天",
        "明日" to "明天",
        "きのう" to "昨天",
        "昨日" to "昨天",
        "いま" to "现在",
        "今" to "现在",
        "これから" to "接下来",
        "いまから" to "现在开始",
        "きょうは" to "今天",
        "こんや" to "今晚",
        "今夜" to "今晚",
        "あさ" to "早上",
        "朝" to "早上",
        "ひる" to "中午",
        "昼" to "中午",
        "よる" to "晚上",
        "夜" to "晚上",
        "今週" to "这周",
        "来週" to "下周",
        "先週" to "上周",
        "さいきん" to "最近",
        "最近" to "最近",
        "まいにち" to "每天",
        "毎日" to "每天",

        // ── 九、1.0.5 实测误译的整句兜底 ──
        // 这几条不是「孤立词分布外」，而是 ASMR 台本里根本没出现过的句子形态：
        // 配信被当成「送货」、チャンネル登録被拆成「登记频道」、全平假名的
        // 「にほんごをべんきょうしています」甚至译出完全无关的内容。
        // 整串收录只影响这几句，表外长句一律仍走模型，行为边界不变。
        "昨日の配信は楽しかったです" to "昨天的直播很开心",
        "昨日の配信は楽しかった" to "昨天的直播很开心",
        "チャンネル登録をお願いします" to "请订阅频道",
        "チャンネル登録お願いします" to "请订阅频道",
        "にほんごをべんきょうしています" to "我在学日语",
        // 「こんにちは」本身已在表内，但词尾拉长的口语写法带「ー」，
        // 而 EDGE_PUNCT 刻意不收「ー」（否则会破坏「コーヒー」这类词），
        // 归一化后仍是「こんにちはー」，需要单独收录才能挡住 int8 首步回归。
        "こんにちはー" to "你好",
        "こんにちわー" to "你好"
    )

    /**
     * 英文表。目标与日语表不同：英语**句子**模型本来就译得不差
     * （「This is a very interesting video.」→「这是一个非常有趣的视频。」），
     * 这张表解决的是另外两件事：
     *
     *  1. 术语口径：opus-mt 按词对齐，不看语境，`live stream` 会给「活流」、
     *     `comment` 会给「注释」——直播场景必须纠正；
     *  2. 固定句截断：`don't forget to subscribe` 只会吐出「别忘了订」，
     *     `thanks for watching` 只给「谢谢你看」。
     *
     * key 一律小写（归一化时统一小写）。`like` 单独收「喜欢」，
     * 直播里的点赞另立 `like and subscribe` 一条。
     */
    private val ENTRIES_EN: Map<String, String> = mapOf(
        // ── 寒暄 / 礼貌 ──
        "hello" to "你好",
        "hi" to "你好",
        "hey" to "嘿",
        "good morning" to "早上好",
        "good afternoon" to "下午好",
        "good evening" to "晚上好",
        "good night" to "晚安",
        "bye" to "再见",
        "goodbye" to "再见",
        "see you" to "再见",
        "see you later" to "回头见",
        "thanks" to "谢谢",
        "thank you" to "谢谢",
        "thank you so much" to "非常感谢",
        "thanks a lot" to "多谢",
        "sorry" to "对不起",
        "i'm sorry" to "我很抱歉",
        "excuse me" to "打扰一下",
        "please" to "请",
        "you're welcome" to "不客气",
        "no problem" to "没问题",
        "yes" to "是",
        "yeah" to "是",
        "yep" to "是",
        "no" to "不",
        "nope" to "不",
        "ok" to "好",
        "okay" to "好",
        "sure" to "当然",
        "of course" to "当然",
        "really" to "真的",
        "wow" to "哇",
        "nice" to "不错",
        "great" to "太好了",
        "awesome" to "太棒了",
        "cool" to "酷",

        // ── 直播 / 视频：这张表最大的价值就在这一段 ──
        "subscribe" to "订阅",
        "please subscribe" to "请订阅",
        "don't forget to subscribe" to "别忘了订阅",
        "like and subscribe" to "点赞并订阅",
        "hit the bell" to "打开小铃铛",
        "notification" to "通知",
        "live stream" to "直播",
        "live" to "直播",
        "stream" to "直播",
        "streaming" to "直播中",
        "video" to "视频",
        "new video" to "新视频",
        "upload" to "上传",
        "premiere" to "首播",
        "chat" to "聊天",
        "viewer" to "观众",
        "subscriber" to "订阅者",
        "playlist" to "播放列表",
        "thumbnail" to "封面",
        "description" to "简介",
        "link in the description" to "链接在简介里",
        "sponsor" to "赞助",
        "membership" to "会员",
        "donate" to "打赏",
        "super chat" to "超级留言",
        "highlight" to "精彩片段",
        "clip" to "切片",
        "replay" to "回放",
        "gameplay" to "游戏实况",
        "tutorial" to "教程",
        "review" to "评测",
        "vlog" to "日常视频",
        "thanks for watching" to "感谢观看",
        "thank you for watching" to "感谢观看",
        "welcome to my channel" to "欢迎来到我的频道",
        "my channel" to "我的频道",
        "channel" to "频道",
        "like" to "喜欢",
        "share" to "分享",
        "comment" to "评论",

        // ── 反应 / 口头禅 ──
        "oh" to "哦",
        "ah" to "啊",
        "uh" to "呃",
        "hmm" to "嗯",
        "well" to "那么",
        "so" to "所以",
        "anyway" to "总之",
        "actually" to "其实",
        "maybe" to "也许",
        "probably" to "大概",
        "definitely" to "一定",
        "exactly" to "没错",
        "right" to "对吧",
        "i see" to "我明白了",
        "i don't know" to "我不知道",
        "i think" to "我觉得",
        "you know" to "你知道",
        "let me see" to "让我看看",
        "wait" to "等等",
        "hold on" to "等一下",
        "look" to "看",
        "listen" to "听",
        "come on" to "来吧",
        "oh my god" to "我的天",
        "what" to "什么",
        "why" to "为什么",
        "how" to "怎么",
        "where" to "哪里",
        "when" to "什么时候",
        "who" to "谁",

        // ── 常用短词 ──
        "water" to "水",
        "food" to "食物",
        "school" to "学校",
        "work" to "工作",
        "home" to "家",
        "friend" to "朋友",
        "family" to "家人",
        "music" to "音乐",
        "movie" to "电影",
        "game" to "游戏",
        "book" to "书",
        "cat" to "猫",
        "dog" to "狗",
        "love" to "爱",
        "happy" to "开心",
        "sad" to "难过",
        "tired" to "累了",
        "hungry" to "饿了",
        "good" to "好",
        "bad" to "坏",
        "big" to "大",
        "small" to "小",
        "new" to "新",
        "old" to "旧",
        "hot" to "热",
        "cold" to "冷",
        "beautiful" to "漂亮",
        "cute" to "可爱",
        "interesting" to "有趣",
        "boring" to "无聊",
        "difficult" to "难",
        "easy" to "简单",
        "money" to "钱",
        "time" to "时间",
        "day" to "天",
        "night" to "晚上",
        "morning" to "早上",
        "today" to "今天",
        "tomorrow" to "明天",
        "yesterday" to "昨天"
    )

    /** 供自检 / 调试使用：两张表的条目总数。 */
    val size: Int get() = ENTRIES.size + ENTRIES_EN.size
}
