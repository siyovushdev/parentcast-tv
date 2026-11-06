package dev.siyovush.parentcast.tv.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

internal data class ChildPlaylistCard(val id:String,val name:String,val ready:Int,val total:Int,val canStart:Boolean,val coverKey:String)

/** A programme chooser only; parent authorization and Android HOME policy stay in MainActivity. */
internal class ChildLibraryView(context:Context,
    private val open:(String)->Unit,
    private val loadCover:(String, (ByteArray?)->Unit)->Unit,
) : FrameLayout(context) {
    private val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(36),dp(24),dp(36),dp(24)) }
    private val grid=GridLayout(context).apply { columnCount=3 }
    private val message=TextView(context).apply { setTextColor(0xffb8cfc6.toInt());textSize=18f;setPadding(0,dp(12),0,dp(16)) }
    private var renderKey:List<ChildPlaylistCard>?=null
    private var generation=0
    private val loaded=mutableSetOf<String>()
    init {
        setBackgroundColor(0xff101f1b.toInt())
        addView(ScrollView(context).apply { isFillViewport=true;addView(body) },LayoutParams(-1,-1))
        body.addView(TextView(context).apply { text="Что будем смотреть?";textSize=30f;setTextColor(Color.WHITE);typeface=android.graphics.Typeface.DEFAULT_BOLD })
        body.addView(message)
        body.addView(grid,LinearLayout.LayoutParams(-1,-2))
        body.addView(TextView(context).apply { text="Выберите программу стрелками и нажмите OK. Для родителя — удерживайте MENU 3 секунды.";textSize=15f;setTextColor(0xffb8cfc6.toInt());setPadding(0,dp(20),0,0) })
    }
    fun render(cards:List<ChildPlaylistCard>) {
        if(cards==renderKey)return
        val focused=findFocus()?.tag as? String
        renderKey=cards;generation++;val version=generation;loaded.clear();grid.removeAllViews()
        message.text=if(cards.isEmpty())"Родитель ещё не выбрал программы для этого экрана." else "Только программы, выбранные родителем"
        cards.forEachIndexed { index,card ->
            val tile=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;isFocusable=true;isClickable=true;tag=card.id
                setPadding(dp(10),dp(10),dp(10),dp(14));background=tileBackground()
                contentDescription="${card.name}. Готово ${card.ready} из ${card.total}. ${if(card.canStart)"Смотреть" else "Ожидает подготовки"}"
            }
            val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(0xff29473b.toInt());contentDescription=null }
            tile.addView(image,LinearLayout.LayoutParams(-1,dp(130)))
            tile.addView(TextView(context).apply { text=card.name;textSize=20f;maxLines=2;setTextColor(Color.WHITE);setPadding(0,dp(10),0,dp(6)) })
            tile.addView(TextView(context).apply { text=if(card.canStart)"Смотреть · ${card.ready}/${card.total} готово" else "Ожидает подготовки · ${card.ready}/${card.total}";textSize=15f;setTextColor(0xffb8e8d4.toInt()) })
            fun requestImage() {
                if(!loaded.add(card.id))return
                loadCover(card.id) { bytes -> if(version==generation && bytes!=null) {
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    if(bitmap!=null)image.setImageBitmap(bitmap)
                } }
            }
            tile.setOnFocusChangeListener { _,hasFocus -> if(hasFocus)requestImage() }
            tile.setOnClickListener { open(card.id) }
            grid.addView(tile,GridLayout.LayoutParams().apply {
                width=0;height=-2;columnSpec=GridLayout.spec(index%3,1f);rowSpec=GridLayout.spec(index/3)
                setMargins(dp(5),dp(5),dp(5),dp(5));setGravity(Gravity.FILL_HORIZONTAL)
            })
            if(index<3)requestImage()
        }
        if(visibility==View.VISIBLE)post {
            (cards.indexOfFirst { it.id==focused }.takeIf { it>=0 } ?: 0).let { grid.getChildAt(it)?.requestFocus() }
        }
    }
    fun focusFirst() { post { if(findFocus()==null)grid.getChildAt(0)?.requestFocus() } }
    private fun tileBackground()=StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),shape(0xff284d3e.toInt(),0xffa6e4c9.toInt(),4))
        addState(intArrayOf(android.R.attr.state_pressed),shape(0xff284d3e.toInt(),0xffa6e4c9.toInt(),4))
        addState(intArrayOf(),shape(0xff192f26.toInt(),0xff355347.toInt(),1))
    }
    private fun shape(fill:Int,line:Int,width:Int)=GradientDrawable().apply {setColor(fill);cornerRadius=dp(16).toFloat();setStroke(dp(width),line)}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}
