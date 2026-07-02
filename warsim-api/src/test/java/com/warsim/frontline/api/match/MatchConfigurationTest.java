package com.warsim.frontline.api.match;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchConfigurationTest {
    @Test void acceptsValidConfiguration(){assertEquals("frontline_offensive",valid().modeId());}
    @Test void rejectsMinimumBelowOne(){assertThrows(IllegalArgumentException.class,()->copy(0,100,60,2700,15,30,"frontline_offensive"));}
    @Test void rejectsMinimumAboveMaximum(){assertThrows(IllegalArgumentException.class,()->copy(80,50,60,2700,15,30,"frontline_offensive"));}
    @Test void rejectsMaximumBelowTwo(){assertThrows(IllegalArgumentException.class,()->copy(1,1,60,2700,15,30,"frontline_offensive"));}
    @Test void rejectsMaximumAboveHundred(){assertThrows(IllegalArgumentException.class,()->copy(1,101,60,2700,15,30,"frontline_offensive"));}
    @Test void rejectsWarmupBelowFive(){assertThrows(IllegalArgumentException.class,()->copy(1,100,4,2700,15,30,"frontline_offensive"));}
    @Test void rejectsRoundBelowSixty(){assertThrows(IllegalArgumentException.class,()->copy(1,100,60,59,15,30,"frontline_offensive"));}
    @Test void rejectsEndingBelowThree(){assertThrows(IllegalArgumentException.class,()->copy(1,100,60,2700,2,30,"frontline_offensive"));}
    @Test void rejectsResetTimeoutBelowFive(){assertThrows(IllegalArgumentException.class,()->copy(1,100,60,2700,15,4,"frontline_offensive"));}
    @Test void rejectsUnknownMode(){assertThrows(IllegalArgumentException.class,()->copy(1,100,60,2700,15,30,"unknown"));}
    @Test void countdownIsDeduplicatedAndSorted(){var c=new MatchConfiguration(true,"frontline_offensive",1,100,true,true,60,2700,15,30,true,true,true,List.of(1,30,1,60,5));assertEquals(List.of(60,30,5,1),c.warmupAnnouncements());}
    @Test void rejectsCountdownAboveWarmup(){assertThrows(IllegalArgumentException.class,()->new MatchConfiguration(true,"frontline_offensive",1,100,true,true,10,2700,15,30,true,true,true,List.of(30)));}
    private static MatchConfiguration valid(){return copy(40,100,60,2700,15,30,"frontline_offensive");}
    private static MatchConfiguration copy(int min,int max,int warm,int round,int end,int reset,String mode){return new MatchConfiguration(true,mode,min,max,true,true,warm,round,end,reset,true,true,true,List.of(Math.min(warm,5)));}
}
