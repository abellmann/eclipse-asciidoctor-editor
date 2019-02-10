/*
 * Copyright 2018 Albert Tregnaghi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 */
package de.jcup.asciidoctoreditor.provider;

import org.asciidoctor.Asciidoctor;

import de.jcup.asciidoctoreditor.AsciiDoctorOSGIWrapper;
import de.jcup.asciidoctoreditor.InstalledAsciidoctor;

/**
 * An instance providing ogsgi asccidoctor or installed variant
 * @author Albert Tregnaghi
 *
 */
public class EclipseAsciiDoctorProvider implements AsciiDoctorInstanceProvider{
    
    public static final EclipseAsciiDoctorProvider INSTANCE = new EclipseAsciiDoctorProvider();
    
    private static Asciidoctor asciidoctorInstalled;
    private static Asciidoctor asciidoctorEmbedded;
    
    EclipseAsciiDoctorProvider(){
        asciidoctorInstalled = new InstalledAsciidoctor();
        asciidoctorEmbedded = AsciiDoctorOSGIWrapper.INSTANCE.getAsciidoctor();
    }
    
    @Override
    public Asciidoctor getAsciiDoctor(boolean installed){
        if (installed){
            return asciidoctorInstalled;
        }else{
            return asciidoctorEmbedded;
        }
    }
}