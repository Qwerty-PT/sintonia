package pt.nuno.sintonia

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import coil.load
import java.text.DateFormat
import java.util.Date

/** Painel com o guia do canal focado: agora (horário, progresso, descrição) e próximos programas. */
class GuidePanel(private val root: View) {

    private val logo: ImageView = root.findViewById(R.id.gLogo)
    private val name: TextView = root.findViewById(R.id.gName)
    private val now: TextView = root.findViewById(R.id.gNow)
    private val rangeRow: View = root.findViewById(R.id.gRangeRow)
    private val range: TextView = root.findViewById(R.id.gRange)
    private val progress: ProgressBar = root.findViewById(R.id.gProgress)
    private val desc: TextView = root.findViewById(R.id.gDesc)
    private val nextTitle: TextView = root.findViewById(R.id.gNextTitle)
    private val upcoming: LinearLayout = root.findViewById(R.id.gUpcoming)
    private val empty: TextView = root.findViewById(R.id.gEmpty)
    private val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)

    var channel: Channel? = null
        private set

    fun bind(ch: Channel?) {
        val changed = ch?.key != channel?.key
        channel = ch
        if (ch == null) { root.visibility = View.INVISIBLE; return }
        root.visibility = View.VISIBLE
        name.text = ch.name
        if (changed) {
            logo.load(ch.logo) {
                error(if (ch.isRadio) R.drawable.ic_radio else R.drawable.ic_tv)
                fallback(if (ch.isRadio) R.drawable.ic_radio else R.drawable.ic_tv)
            }
        }

        val t = System.currentTimeMillis()
        val list = EpgRepo.upcoming(ch, t, 5)
        val cur = list.firstOrNull()?.takeIf { it.start <= t }
        val next = if (cur != null) list.drop(1) else list

        if (cur != null) {
            now.text = cur.title
            range.text = "${fmt.format(Date(cur.start))} – ${fmt.format(Date(cur.stop))}"
            progress.progress = (((t - cur.start).toDouble() / (cur.stop - cur.start)) * 1000).toInt().coerceIn(0, 1000)
            desc.text = cur.desc.orEmpty()
            now.visibility = View.VISIBLE
            rangeRow.visibility = View.VISIBLE
            desc.visibility = if (cur.desc.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            now.visibility = View.GONE
            rangeRow.visibility = View.GONE
            desc.visibility = View.GONE
        }

        upcoming.removeAllViews()
        next.take(4).forEach { p ->
            upcoming.addView(TextView(root.context).apply {
                text = "${fmt.format(Date(p.start))}   ${p.title}"
                setTextColor(androidx.core.content.ContextCompat.getColor(root.context, R.color.text))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        nextTitle.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE

        if (list.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.setText(if (EpgRepo.state.value == EpgRepo.State.LOADING) R.string.guide_wait else R.string.no_guide)
        } else empty.visibility = View.GONE
    }

    /** Volta a desenhar o mesmo canal (guia atualizado / passou um minuto). */
    fun refresh() = bind(channel)
}
