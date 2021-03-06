/*
 * Copyright (c) 2017 Guus der Kinderen. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nl.goodbytes.xmpp.xep0363;

import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.AbstractComponent;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

import java.net.URL;

/**
 * A XMPP component that implements XEP-0363.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 * @see <a href="http://xmpp.org/extensions/xep-0363.html">XEP-0363</a>
 */
public class Component extends AbstractComponent
{
    public final static String NAMESPACE = "urn:xmpp:http:upload";
    private static final Logger Log = LoggerFactory.getLogger( Component.class );
    private final String name;
    private final String endpoint;

    /**
     * Instantiates a new component.
     *
     * The URL that's provided in the second argument is used as the base URL for all client interaction. More
     * specifically, this value is used to generate the slot filenames. The URL should be accessible for end-users.
     *
     * @param name     The component name (cannot be null or an empty String).
     * @param endpoint The base URL for HTTP interaction (cannot be null).
     */
    public Component( String name, URL endpoint )
    {
        super();

        if ( name == null || name.trim().isEmpty() )
        {
            throw new IllegalArgumentException( "Argument 'name' cannot be null or an empty String." );
        }
        if ( endpoint == null )
        {
            throw new IllegalArgumentException( "Argument 'endpoint' cannot be null." );
        }
        this.name = name.trim();
        this.endpoint = endpoint.toExternalForm();
    }

    @Override
    public String getDescription()
    {
        return "HTTP File Upload, an implementation of XEP-0363, supporting exchange of files between XMPP entities.";
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    protected String discoInfoIdentityCategory()
    {
        return "store"; // TODO: the XEP example reads 'store' but I'm unsure if this is a registered type.
    }

    @Override
    protected String discoInfoIdentityCategoryType()
    {
        return "file"; // TODO: the XEP example reads 'file' but I'm unsure if this is a registered type.
    }

    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return new String[] { NAMESPACE };
    }

    @Override
    protected IQ handleDiscoInfo( IQ iq )
    {
        final IQ response = super.handleDiscoInfo( iq );

        // Add service configuration / preconditions if these exist.
        if ( SlotManager.getInstance().getMaxFileSize() > 0 )
        {
            final Element configForm = response.getChildElement().addElement( "x", "jabber:x:data" );
            configForm.addAttribute( "type", "result" );
            configForm.addElement( "field" ).addAttribute( "var", "FORM_TYPE" ).addAttribute( "type", "hidden" ).addElement( "value" ).addText( NAMESPACE );
            configForm.addElement( "field" ).addAttribute( "var", "max-file-size" ).addText( Long.toString( SlotManager.getInstance().getMaxFileSize() ) );
        }

        return response;
    }

    @Override
    protected IQ handleIQGet( IQ iq ) throws Exception
    {
        final Element request = iq.getChildElement();
        if ( !NAMESPACE.equals( request.getNamespaceURI() ) || !request.getName().equals( "request" ) )
        {
            return null;
        }

        Log.info( "Entity '{}' tries to obtain slot.", iq.getFrom() );
        if ( request.element( "filename" ) == null || request.element( "filename" ).getTextTrim().isEmpty() )
        {
            final IQ response = IQ.createResultIQ( iq );
            response.setError( PacketError.Condition.bad_request );
            return response;
        }

        if ( request.element( "size" ) == null || request.element( "size" ).getTextTrim().isEmpty() )
        {
            final IQ response = IQ.createResultIQ( iq );
            response.setError( PacketError.Condition.bad_request );
            return response;
        }

        final String fileName = request.element( "filename" ).getTextTrim(); // TODO validate the file name (path traversal, etc).
        final long fileSize;
        try
        {
            fileSize = Long.parseLong( request.element( "size" ).getTextTrim() );
        }
        catch ( NumberFormatException e )
        {
            final IQ response = IQ.createResultIQ( iq );
            response.setError( PacketError.Condition.bad_request );
            return response;
        }

        final SlotManager manager = SlotManager.getInstance();
        final Slot slot;
        try
        {
            slot = manager.getSlot( iq.getFrom(), fileName, fileSize );
        }
        catch ( TooLargeException ex )
        {
            final IQ response = IQ.createResultIQ( iq );
            final PacketError error = new PacketError( PacketError.Condition.not_acceptable, PacketError.Type.modify, "File too large. Maximum file size is " + ex.getMaximum() + " bytes." );
            error.getElement().addElement( "file-too-large", NAMESPACE ).addElement( "max-file-size" ).addText( Long.toString( ex.getMaximum() ) );
            response.setError( error );
            return response;
        }


        final URL url = new URL( endpoint + "/" + slot.getUuid() + "/" + fileName );

        Log.info( "Entity '{}' obtained slot for '{}' ({} bytes): {}", iq.getFrom(), fileName, fileSize, url.toExternalForm() );

        final IQ response = IQ.createResultIQ( iq );
        final Element slotElement = response.setChildElement( "slot", NAMESPACE );
        slotElement.addElement( "put" ).setText( url.toExternalForm() );
        slotElement.addElement( "get" ).setText( url.toExternalForm() );
        return response;
    }
}
