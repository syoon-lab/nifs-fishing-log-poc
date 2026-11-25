package kr.go.nifs.fieldrecorder;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Larger app dialogs without changing listeners, stored data, or system font settings. */
final class ReadableDialog {
    private ReadableDialog() {}
    private static int dp(Context context,int value) {
        return Math.round(value*context.getResources().getDisplayMetrics().density);
    }
    private static void text(TextView view,int size) {
        view.setTextSize(size); // SP: respect the user's system font scale.
        view.setSingleLine(false);
        view.setMaxLines(Integer.MAX_VALUE);
        view.setEllipsize(null);
        view.setHorizontallyScrolling(false);
    }

    static void style(AlertDialog dialog) {
        Context context=dialog.getContext();
        Window window=dialog.getWindow();
        if(window==null)return;
        int width=context.getResources().getDisplayMetrics().widthPixels-dp(context,24);
        window.setLayout(Math.max(dp(context,240),width),ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null)text(message,22);
        int titleId=context.getResources().getIdentifier("alertTitle","id","android");
        TextView title=titleId==0?null:dialog.findViewById(titleId);
        if(title!=null)text(title,28);

        int panelId=context.getResources().getIdentifier("buttonPanel","id","android");
        View panelView=panelId==0?null:dialog.findViewById(panelId);
        if(!(panelView instanceof ViewGroup))return;
        ViewGroup panel=(ViewGroup)panelView;
        java.util.ArrayList<Button> buttons=new java.util.ArrayList<>();
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
            Button button=dialog.getButton(which);
            if(button==null||button.getVisibility()!=View.VISIBLE)continue;
            text(button,24);button.setAllCaps(false);button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(context,64));button.setMinimumHeight(dp(context,64));
            button.setPadding(dp(context,12),dp(context,8),dp(context,12),dp(context,8));
            buttons.add(button);
        }
        if(buttons.isEmpty())return;
        // OEM dialogs may nest each button in a different row. Keep the actual
        // buttons and listeners, replacing only their layout containers.
        for(Button button:buttons)if(button.getParent() instanceof ViewGroup)((ViewGroup)button.getParent()).removeView(button);
        panel.removeAllViews();
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);
        for(Button button:buttons) {
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(context,2),0,dp(context,2));row.addView(button,lp);
        }
        panel.addView(row,new ViewGroup.LayoutParams(-1,-2));
        ViewGroup.LayoutParams outer=panel.getLayoutParams();outer.width=-1;outer.height=-2;
        if(outer instanceof LinearLayout.LayoutParams)((LinearLayout.LayoutParams)outer).weight=0;
        panel.setLayoutParams(outer);
        int availableWidth=Math.max(dp(context,120),width-dp(context,48));
        row.measure(View.MeasureSpec.makeMeasureSpec(availableWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        panel.setMinimumHeight(row.getMeasuredHeight()+panel.getPaddingTop()+panel.getPaddingBottom());

    }

    static final class Builder extends AlertDialog.Builder {
        Builder(Context context){super(context);}
        @Override public AlertDialog create() {
            AlertDialog dialog=super.create();
            dialog.setOnShowListener(ignored->style(dialog));
            return dialog;
        }
        private ArrayAdapter<CharSequence> items(CharSequence[] labels,boolean singleChoice) {
            return new ArrayAdapter<CharSequence>(getContext(),singleChoice?android.R.layout.select_dialog_singlechoice:android.R.layout.select_dialog_item,android.R.id.text1,labels) {
                @Override public View getView(int position,View convertView,ViewGroup parent) {
                    View view=super.getView(position,convertView,parent);
                    TextView label=view.findViewById(android.R.id.text1);
                    text(label,24);label.setGravity(Gravity.CENTER_VERTICAL);label.setMinHeight(dp(getContext(),64));
                    label.setPadding(dp(getContext(),20),dp(getContext(),12),dp(getContext(),20),dp(getContext(),12));
                    ViewGroup.LayoutParams params=view.getLayoutParams();
                    if(params!=null){params.height=ViewGroup.LayoutParams.WRAP_CONTENT;view.setLayoutParams(params);}
                    return view;
                }
            };
        }
        @Override public Builder setItems(CharSequence[] labels,DialogInterface.OnClickListener listener) {
            super.setAdapter(items(labels,false),listener);return this;
        }
        @Override public Builder setSingleChoiceItems(CharSequence[] labels,int checked,DialogInterface.OnClickListener listener) {
            super.setSingleChoiceItems(items(labels,true),checked,listener);return this;
        }
    }
}
