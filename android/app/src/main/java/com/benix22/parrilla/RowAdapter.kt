package com.benix22.parrilla

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lista de filas de texto que se rellena bajo demanda (hay listas con miles de canales). */
class RowAdapter(
    private val onClick: (Int) -> Unit,
    private val onLong: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    var size: Int = 0
    var selected: Int = -1
    var label: (Int) -> String = { "" }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_row, parent, false) as TextView
        return VH(tv)
    }

    override fun getItemCount(): Int = size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = label(position)
        holder.tv.isSelected = position == selected
        holder.tv.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        holder.tv.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p)
            true
        }
    }
}
