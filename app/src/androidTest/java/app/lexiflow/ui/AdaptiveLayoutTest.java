package app.lexiflow.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lexiflow.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AdaptiveLayoutTest {
    @Test public void narrowContentFillsWindowAndWideContentStaysCenteredAtLargeText() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            verifyWidth(instrumentation.getTargetContext(),360,360,24);
            verifyWidth(instrumentation.getTargetContext(),960,720,32);
        });
    }

    private static void verifyWidth(Context base,int widthDp,int contentDp,int paddingDp) {
        var override = new Configuration();
        override.smallestScreenWidthDp = widthDp;
        override.screenWidthDp = widthDp;
        override.fontScale = 1.5f;
        var context = new ContextThemeWrapper(base.createConfigurationContext(override),R.style.Theme_LexiFlow);
        View root = LayoutInflater.from(context).inflate(R.layout.activity_main,null);
        TextView body = root.findViewById(R.id.body);
        body.setText(String.join(" ",java.util.Collections.nCopies(12,"Многострочный пример для проверки переноса текста.")));
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp*density);
        int height = Math.round(640*density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
        View content = root.findViewById(R.id.screen_content);
        assertEquals(Math.round(contentDp*density),content.getWidth());
        assertEquals(Math.round(paddingDp*density),content.getPaddingStart());
        assertTrue(Math.abs((width-content.getWidth())/2-content.getLeft()) <= 1);
        assertTrue(body.getLineCount() > 1);
    }
}
