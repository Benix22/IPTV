package com.benix22.parrilla

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Rejilla de tarjetas con carátula: pósters para películas y series, formato ancho para canales. */
class CardAdapter(
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    companion object {
        const val POSTER_W = 150
        const val POSTER_H = 225
        const val WIDE_W = 220
        const val WIDE_H = 124
    }

    var size = 0
    var selected = -1
    var poster = true
    var title: (Int) -> String = { "" }
    var logo: (Int) -> String = { "" }
    var fav: (Int) -> Boolean = { false }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: FrameLayout = v.findViewById(R.id.frame)
        val img: ImageView = v.findViewById(R.id.img)
        val ph: TextView = v.findViewById(R.id.ph)
        val star: TextView = v.findViewById(R.id.star)
        val title: TextView = v.findViewById(R.id.title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        v.setOnFocusChangeListener { view, focused ->
            val s = if (focused) 1.07f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            if (focused) view.elevation = 8f else view.elevation = 0f
        }
        return VH(v)
    }

    override fun getItemCount(): Int = size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = h.itemView.resources.displayMetrics.density
        val w = ((if (poster) POSTER_W else WIDE_W) * d).toInt()
        val ht = ((if (poster) POSTER_H else WIDE_H) * d).toInt()
        val lp = h.frame.layoutParams
        if (lp.width != w || lp.height != ht) {
            lp.width = w
            lp.height = ht
            h.frame.layoutParams = lp
        }
        val tlp = h.title.layoutParams
        if (tlp.width != w) {
            tlp.width = w
            h.title.layoutParams = tlp
        }

        val name = title(position)
        h.title.text = name
        h.star.visibility = if (fav(position)) View.VISIBLE else View.GONE
        h.itemView.isSelected = position == selected

        h.ph.text = name.trim().take(2).uppercase()
        h.ph.visibility = View.VISIBLE
        h.img.scaleType = if (poster) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        h.img.setPadding(0, 0, 0, 0)
        if (!poster) {
            val pad = (10 * d).toInt()
            h.img.setPadding(pad, pad, pad, pad)
        }

        val url = logo(position)
        h.img.dispose()
        h.img.setImageDrawable(null)
        if (url.startsWith("http")) {
            h.img.load(url) {
                crossfade(150)
                size(w, ht)
                listener(onSuccess = { _, _ -> h.ph.visibility = View.GONE })
            }
        }

        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p)
            true
        }
    }

    override fun onViewRecycled(h: VH) {
        h.img.dispose()
        h.img.setImageDrawable(null)
    }
}
