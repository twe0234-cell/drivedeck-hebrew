package android.speech.tts;
public class TextToSpeech {
  public interface OnInitListener { void onInit(int status); }
  public TextToSpeech(android.content.Context c, OnInitListener l){}
  public int setLanguage(java.util.Locale l){return 0;}
  public int speak(CharSequence t, int q, android.os.Bundle p, String id){return 0;}
  public int setOnUtteranceProgressListener(UtteranceProgressListener l){return 0;}
  public int stop(){return 0;} public void shutdown(){}
}
