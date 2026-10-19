package pt.nuno.sintonia

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * ▲▼ com o comando: vai sempre para o item vizinho da MESMA lista (fazendo scroll se ainda não
 * estiver desenhado). Sem isto, no fundo do ecrã o Android "salta" para a lista do lado.
 * No fim da lista fica parado; no topo deixa sair (ex.: para os botões da barra de cima).
 */
internal fun moveListFocus(item: View, from: Int, delta: Int, count: Int): Boolean {
    val rv = item.parent as? RecyclerView ?: return false
    val target = from + delta
    if (target < 0) return false
    if (target >= count) return true
    val vh = rv.findViewHolderForAdapterPosition(target)
    if (vh != null) {
        vh.itemView.requestFocus()
    } else {
        rv.scrollToPosition(target)
        rv.post { rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
    }
    return true
}

private fun View.focusZoom(on: Boolean) {
    animate().scaleX(if (on) 1.03f else 1f).scaleY(if (on) 1.03f else 1f).setDuration(120).start()
}

/** Lista de grupos (vertical na TV/paisagem, em "fitas" horizontais no telemóvel). */
class GroupAdapter(
    private val vertical: Boolean,
    private val onLongClick: ((Int) -> Unit)? = null,
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<GroupAdapter.VH>() {

    var items: List<String> = emptyList()
        private set
    var selected = 0
        private set

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<String>, sel: Int) {
        items = list; selected = sel; notifyDataSetChanged()
    }

    /** Marca o grupo escolhido sem refazer as vistas (não estraga o foco do comando). */
    fun select(pos: Int, rv: RecyclerView) {
        selected = pos
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            child.isSelected = rv.getChildAdapterPosition(child) == pos
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false) as TextView
        if (vertical) v.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.text.text = items[position]
        h.text.isSelected = position == selected
        h.text.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onSelect) }
        if (onLongClick != null) {
            h.text.setOnLongClickListener {
                h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true
            }
        }
        if (vertical) {
            h.text.setOnKeyListener { v, keyCode, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val pos = h.bindingAdapterPosition
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveListFocus(v, pos, +1, itemCount)
                    KeyEvent.KEYCODE_DPAD_UP -> moveListFocus(v, pos, -1, itemCount)
                    else -> false
                }
            }
        }
        h.text.setOnFocusChangeListener { v, has ->
            v.focusZoom(has)
            if (has) h.bindingAdapterPosition.takeIf { it >= 0 && it != selected }?.let(onSelect)
        }
    }
}

/** Lista de canais com logótipo. */
class ChannelAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onLeft: (() -> Unit)?,
    /** Canal focado com o comando (para o painel do guia). */
    private val onFocus: ((Int) -> Unit)? = null,
    /** ▶ num canal (ex.: ir para a janela do vídeo). */
    private val onRight: (() -> Unit)? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
        private set
    var favourites: Set<String> = emptySet()
    var showGroup = false

    companion object {
        const val PAYLOAD_EPG = "epg"
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val star: TextView = v.findViewById(R.id.star)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Channel>, withGroup: Boolean) {
        items = list; showGroup = withGroup; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    /** Só atualiza a linha do guia (sem refazer a vista nem perder o foco). */
    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_EPG)) bindSub(h, items[position]) else onBindViewHolder(h, position)
    }

    private fun bindSub(h: VH, ch: Channel) {
        val now = EpgRepo.nowAndNext(ch).first
        val parts = buildList {
            if (showGroup) add(ch.group)
            if (now != null) add("▶ ${now.title}")
            else if (ch.isRadio && !showGroup) add(h.itemView.context.getString(R.string.radio))
        }
        h.sub.text = parts.joinToString(" · ")
        h.sub.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        h.name.text = ch.name
        bindSub(h, ch)
        h.star.visibility = if (ch.key in favourites) View.VISIBLE else View.GONE
        h.logo.load(ch.logo) {
            crossfade(true)
            placeholder(R.drawable.ic_tv)
            error(if (ch.isRadio) R.drawable.ic_radio else R.drawable.ic_tv)
            fallback(if (ch.isRadio) R.drawable.ic_radio else R.drawable.ic_tv)
        }
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        h.itemView.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            v.focusZoom(has)
            if (has) h.bindingAdapterPosition.takeIf { it >= 0 }?.let { onFocus?.invoke(it) }
        }
        h.itemView.setOnKeyListener { _, keyCode, e ->
            when {
                e.action != KeyEvent.ACTION_DOWN -> false
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ->
                    moveListFocus(h.itemView, h.bindingAdapterPosition, +1, itemCount)
                keyCode == KeyEvent.KEYCODE_DPAD_UP ->
                    moveListFocus(h.itemView, h.bindingAdapterPosition, -1, itemCount)
                onLeft != null && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft.invoke(); true }
                onRight != null && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { onRight.invoke(); true }
                else -> false
            }
        }
    }
}
