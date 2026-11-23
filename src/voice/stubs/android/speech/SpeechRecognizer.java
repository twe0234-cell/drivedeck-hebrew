package android.speech;
public class SpeechRecognizer {
  public static boolean isRecognitionAvailable(android.content.Context c){return false;}
  public static SpeechRecognizer createSpeechRecognizer(android.content.Context c){return null;}
  public void setRecognitionListener(RecognitionListener l){} public void startListening(android.content.Intent i){}
  public void stopListening(){} public void cancel(){} public void destroy(){}
}
