package com.example.Settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;


import com.example.ahd_camera.R;

import java.util.List;

/**
 * File: SettingMenuAdapter.java
 * Author: mine
 * Create: 2026/6/16 17:16
 * Changes (from 2026/6/16)
 */
public class SettingMenuAdapter extends RecyclerView.Adapter<SettingMenuAdapter.MenuViewHolder> {
    public interface OnMenuClickListener {
        void onMenuClick(int position, String name);
    }

    private final List<String> menuList;
    private final OnMenuClickListener listener;
    private int selectedPosition = 0;

    public SettingMenuAdapter(List<String> menuList, OnMenuClickListener listener) {
        this.menuList = menuList;
        this.listener = listener;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setSelectedPosition(int position) {
        if (menuList == null || menuList.isEmpty()) {
            selectedPosition = 0;
            return;
        }

        int clampedPosition = Math.max(0, Math.min(position, menuList.size() - 1));
        if (selectedPosition == clampedPosition) {
            return;
        }

        int oldPosition = selectedPosition;
        selectedPosition = clampedPosition;
        notifyItemChanged(oldPosition);
        notifyItemChanged(selectedPosition);
    }

    @Override
    public MenuViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_setting_menu, parent, false);
        return new MenuViewHolder(view);
    }

    @Override
    public void onBindViewHolder(MenuViewHolder holder, int position) {
        String name = menuList.get(position);

        holder.tvMenuName.setText(name);
        holder.itemView.setFocusable(true);
        holder.itemView.setFocusableInTouchMode(true);

        boolean selected = position == selectedPosition;
        holder.itemView.setSelected(selected);
        holder.tvMenuName.setSelected(selected);
        holder.tvMenuName.setTextColor(ContextCompat.getColor(
                holder.itemView.getContext(),
                selected ? R.color.white : R.color.settings_menu_text
        ));

        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) {
                return;
            }

            setSelectedPosition(adapterPosition);

            if (listener != null) {
                listener.onMenuClick(selectedPosition, menuList.get(selectedPosition));
            }
        });
    }

    @Override
    public int getItemCount() {
        return menuList == null ? 0 : menuList.size();
    }

    static class MenuViewHolder extends RecyclerView.ViewHolder {

        TextView tvMenuName;

        public MenuViewHolder(View itemView) {
            super(itemView);
            tvMenuName = itemView.findViewById(R.id.tv_menu_name);
        }
    }
}
