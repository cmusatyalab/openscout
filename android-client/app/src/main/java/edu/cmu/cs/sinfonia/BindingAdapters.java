package edu.cmu.cs.sinfonia;

import android.util.Log;
import android.view.LayoutInflater;
import android.widget.LinearLayout;

import androidx.databinding.BindingAdapter;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;

import edu.cmu.cs.openscout.databinding.BackendDetailBinding;

public class BindingAdapters {
    private static final String TAG = "OpenScout/BindingAdapters";

    @BindingAdapter({"items", "fragment", "layout"})
    public static <E> void setItems(
            LinearLayout view,
            ArrayList<E> items,
            Fragment fragment,
            int layoutResId
    ) {
        Log.i(TAG, "setItems");
        if (fragment instanceof SinfoniaFragment) {
            SinfoniaFragment sinfoniaFragment = (SinfoniaFragment) fragment;
            view.removeAllViews();
            if (items != null) {
                LayoutInflater inflater = LayoutInflater.from(view.getContext());
                for (E item : items) {
                    BackendDetailBinding binding = DataBindingUtil.inflate(inflater, layoutResId, view, false);
                    Backend backend = (Backend) item;
                    binding.setItem(backend);
                    binding.setFragment(sinfoniaFragment);
                    view.addView(binding.getRoot());
                }
            }
        }
    }
}
