/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.directory.server.core.entry;


import org.apache.directory.shared.ldap.NotImplementedException;
import org.apache.directory.shared.ldap.entry.AbstractBinaryValue;
import org.apache.directory.shared.ldap.schema.AttributeType;
import org.apache.directory.shared.ldap.schema.ByteArrayComparator;
import org.apache.directory.shared.ldap.schema.MatchingRule;
import org.apache.directory.shared.ldap.schema.Normalizer;
import org.apache.directory.shared.ldap.util.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingException;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.Comparator;


/**
 * A server side schema aware wrapper around a binary attribute value.
 * This value wrapper uses schema information to syntax check values,
 * and to compare them for equality and ordering.  It caches results
 * and invalidates them when the wrapped value changes.
 *
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 * @version $Rev$, $Date$
 */
public class ServerBinaryValue extends AbstractBinaryValue implements ServerValue<byte[]>, Externalizable
{
    /** Used for serialization */
    public static final long serialVersionUID = 2L;
    
    /** logger for reporting errors that might not be handled properly upstream */
    private static final Logger LOG = LoggerFactory.getLogger( ServerBinaryValue.class );

    /** reference to the attributeType which is not serialized */
    private transient AttributeType attributeType;

    /** the canonical representation of the wrapped binary value */
    private transient byte[] normalizedValue;
    
    /** A flag set if the normalized data is different from the wrapped data */
    private transient boolean same;

    /** cached results of the isValid() method call */
    private transient Boolean valid;


    /**
     * Creates a ServerBinaryValue without an initial wrapped value.
     *
     * @param attributeType the schema type associated with this ServerBinaryValue
     */
    public ServerBinaryValue( AttributeType attributeType )
    {
        assert checkAttributeType( attributeType) == null : logAssert( checkAttributeType( attributeType ) );

        try
        {
            if ( attributeType.getSyntax().isHumanReadable() )
            {
                LOG.warn( "Treating a value of a human readible attribute {} as binary: ", attributeType.getName() );
            }
        }
        catch( NamingException e )
        {
            LOG.error( "Failed to resolve syntax for attributeType {}", attributeType, e );
        }

        this.attributeType = attributeType;
        setNormalized( false );
    }


    /**
     * Creates a ServerBinaryValue with an initial wrapped binary value.
     *
     * @param attributeType the schema type associated with this ServerBinaryValue
     * @param wrapped the binary value to wrap which may be null, or a zero length byte array
     */
    public ServerBinaryValue( AttributeType attributeType, byte[] wrapped )
    {
        this( attributeType );
        super.set( wrapped );
        setNormalized( false );
    }


    /**
     * Creates a ServerStringValue with an initial wrapped String value and
     * a normalized value.
     *
     * @param attributeType the schema type associated with this ServerStringValue
     * @param wrapped the value to wrap which can be null
     * @param normalizedValue the normalized value
     */
    /** No protection */ ServerBinaryValue( AttributeType attributeType, byte[] wrapped, byte[] normalizedValue, boolean same, boolean valid )
    {
        setNormalized( true );
        this.attributeType = attributeType;
        super.set( wrapped );
        setNormalizedValue( normalizedValue );
        this.same = same;
        this.valid = valid;
    }


    // -----------------------------------------------------------------------
    // Value<String> Methods
    // -----------------------------------------------------------------------


    /**
     * Sets the wrapped binary value.  Has the side effect of setting the
     * normalizedValue and the valid flags to null if the wrapped value is
     * different than what is already set.  These cached values must be
     * recomputed to be correct with different values.
     *
     * @see ServerValue#set(Object)
     */
    public final void set( byte[] wrapped )
    {
        // Why should we invalidate the normalized value if it's we're setting the
        // wrapper to it's current value?
        byte[] value = getReference();
        
        if ( value != null )
        {
            if ( Arrays.equals( wrapped, value ) )
            {
                return;
            }
        }

        normalizedValue = null;
        valid = null;
        setNormalized( false );
        super.set( wrapped );
    }


    // -----------------------------------------------------------------------
    // ServerValue<byte[]> Methods
    // -----------------------------------------------------------------------
    public void normalize() throws NamingException
    {
        if ( isNormalized() )
        {
            // Bypass the normalization if it has already been done. 
            return;
        }
        
        if ( getReference() != null )
        {
            Normalizer normalizer = getNormalizer();
    
            if ( normalizer == null )
            {
                normalizedValue = getCopy();
                setNormalized( false );
            }
            else
            {
                normalizedValue = ( byte[] ) normalizer.normalize( getCopy() );
                setNormalized( true );
            }
            
            if ( Arrays.equals( super.getReference(), normalizedValue ) )
            {
                same = true;
            }
            else
            {
                same = false;
            }
        }
        else
        {
            normalizedValue = null;
            same = true;
            setNormalized( false );
        }
    }

    
    /**
     * Gets the normalized (cannonical) representation for the wrapped string.
     * If the wrapped String is null, null is returned, otherwise the normalized
     * form is returned.  If no the normalizedValue is null, then this method
     * will attempt to generate it from the wrapped value: repeated calls to
     * this method do not unnecessarily normalize the wrapped value.  Only changes
     * to the wrapped value result in attempts to normalize the wrapped value.
     *
     * @return a reference to the normalized version of the wrapped value
     * @throws NamingException with failures to normalize
     */
    public byte[] getNormalizedReference() throws NamingException
    {
        if ( isNull() )
        {
            return null;
        }

        if ( !isNormalized() )
        {
            normalize();
        }

        return normalizedValue;
    }


    /**
     * Sets this value's wrapped value to a copy of the src array.
     *
     * @param wrapped the byte array to use as the wrapped value
     */
    private void setNormalizedValue( byte[] normalizedValue )
    {
        if ( normalizedValue != null )
        {
            this.normalizedValue = new byte[ normalizedValue.length ];
            System.arraycopy( normalizedValue, 0, this.normalizedValue, 0, normalizedValue.length );
        }
        else
        {
            this.normalizedValue = null;
        }
    }


    /**
     * Gets a direct reference to the normalized representation for the
     * wrapped value of this ServerValue wrapper. Implementations will most
     * likely leverage the attributeType this value is associated with to
     * determine how to properly normalize the wrapped value.
     *
     * @return the normalized version of the wrapped value
     * @throws NamingException if schema entity resolution fails or normalization fails
     */
    public byte[] getNormalizedCopy() throws NamingException
    {
        if ( normalizedValue == null )
        {
            getNormalizedReference();
        }

        byte[] copy = new byte[ normalizedValue.length ];
        System.arraycopy( normalizedValue, 0, copy, 0, normalizedValue.length );
        return copy;
    }


    /**
     * Uses the syntaxChecker associated with the attributeType to check if the
     * value is valid.  Repeated calls to this method do not attempt to re-check
     * the syntax of the wrapped value every time if the wrapped value does not
     * change. Syntax checks only result on the first check, and when the wrapped
     * value changes.
     *
     * @see ServerValue#isValid()
     */
    public final boolean isValid() throws NamingException
    {
        if ( valid != null )
        {
            return valid;
        }

        valid = attributeType.getSyntax().getSyntaxChecker().isValidSyntax( getReference() );
        return valid;
    }

    
    /**
     * @return Tells if the wrapped value and the normalized value are the same 
     */
    public final boolean isSame()
    {
        return same;
    }
    

    /**
     *
     * @see ServerValue#compareTo(ServerValue)
     * @throws IllegalStateException on failures to extract the comparator, or the
     * normalizers needed to perform the required comparisons based on the schema
     */
    public int compareTo( ServerValue<byte[]> value )
    {
        if ( isNull() )
        {
            if ( ( value == null ) || value.isNull() )
            {
                return 0;
            }
            else
            {
                return -1;
            }
        }
        else
        {
            if ( ( value == null ) || value.isNull() ) 
            {
                return 1;
            }
        }

        if ( value instanceof ServerBinaryValue )
        {
            ServerBinaryValue binaryValue = ( ServerBinaryValue ) value;

            // Normalizes the compared value
            try
            {
                binaryValue.normalize();
            }
            catch ( NamingException ne )
            {
                LOG.error( "Cannot nnormalize the wrapped value '" + binaryValue.get() + "'" );
            }
            
            // Normalizes the value
            try
            {
                normalize();
            }
            catch ( NamingException ne )
            {
                LOG.error( "Cannot normalize the wrapped value '" + get() + "'" );
            }
            try
            {
                Comparator comparator = getComparator();
                
                if ( comparator != null )
                {
                    return getComparator().compare( getNormalizedReference(), binaryValue.getNormalizedReference() );
                }
                else
                {
                    return ByteArrayComparator.INSTANCE.compare( getNormalizedReference(), binaryValue.getNormalizedReference() );
                }
            }
            catch ( NamingException e )
            {
                String msg = "Failed to compare normalized values for " + Arrays.toString( getReference() )
                        + " and " + value;
                LOG.error( msg, e );
                throw new IllegalStateException( msg, e );
            }
        }

        throw new NotImplementedException( "I don't really know how to compare anything other " +
                "than ServerBinaryValues at this point in time." );
    }


    public AttributeType getAttributeType()
    {
        return attributeType;
    }


    /**
     * @see ServerValue#instanceOf(AttributeType)
     */
    public boolean instanceOf( AttributeType attributeType ) throws NamingException
    {
        if ( this.attributeType.equals( attributeType ) )
        {
            return true;
        }

        return this.attributeType.isDescentantOf( attributeType );
    }


    // -----------------------------------------------------------------------
    // Object Methods
    // -----------------------------------------------------------------------


    /**
     * @see Object#hashCode()
     * @throws IllegalStateException on failures to extract the comparator, or the
     * normalizers needed to perform the required comparisons based on the schema
     */
    public int hashCode()
    {
        // return zero if the value is null so only one null value can be
        // stored in an attribute - the string version does the same
        if ( isNull() )
        {
            return 0;
        }

        try
        {
            return Arrays.hashCode( getNormalizedReference() );
        }
        catch ( NamingException e )
        {
            String msg = "Failed to normalize \"" + toString() + "\" while trying to get hashCode()";
            LOG.error( msg, e );
            throw new IllegalStateException( msg, e );
        }
    }


    /**
     * Checks to see if this ServerBinaryValue equals the supplied object.
     *
     * This equals implementation overrides the BinaryValue implementation which
     * is not schema aware.
     * @throws IllegalStateException on failures to extract the comparator, or the
     * normalizers needed to perform the required comparisons based on the schema
     */
    public boolean equals( Object obj )
    {
        if ( this == obj )
        {
            return true;
        }

        if ( ! ( obj instanceof ServerBinaryValue ) )
        {
            return false;
        }

        ServerBinaryValue other = ( ServerBinaryValue ) obj;
        
        if ( isNull() && other.isNull() )
        {
            return true;
        }

        if ( isNull() != other.isNull() )
        {
            return false;
        }

        return compareTo( (ServerValue<byte[]>)other ) == 0;
    }


    // -----------------------------------------------------------------------
    // Private Helper Methods (might be put into abstract base class)
    // -----------------------------------------------------------------------


    /**
     * Find a matchingRule to use for normalization and comparison.  If an equality
     * matchingRule cannot be found it checks to see if other matchingRules are
     * available: SUBSTR, and ORDERING.  If a matchingRule cannot be found null is
     * returned.
     *
     * @return a matchingRule or null if one cannot be found for the attributeType
     * @throws NamingException if resolution of schema entities fail
     */
    private MatchingRule getMatchingRule() throws NamingException
    {
        MatchingRule mr = attributeType.getEquality();

        if ( mr == null )
        {
            mr = attributeType.getOrdering();
        }

        if ( mr == null )
        {
            mr = attributeType.getSubstr();
        }

        return mr;
    }


    /**
     * Gets a normalizer using getMatchingRule() to resolve the matchingRule
     * that the normalizer is extracted from.
     *
     * @return a normalizer associated with the attributeType or null if one cannot be found
     * @throws NamingException if resolution of schema entities fail
     */
    private Normalizer getNormalizer() throws NamingException
    {
        MatchingRule mr = getMatchingRule();

        if ( mr == null )
        {
            return null;
        }

        return mr.getNormalizer();
    }


    /**
     * Gets a comparator using getMatchingRule() to resolve the matching
     * that the comparator is extracted from.
     *
     * @return a comparator associated with the attributeType or null if one cannot be found
     * @throws NamingException if resolution of schema entities fail
     */
    private Comparator getComparator() throws NamingException
    {
        MatchingRule mr = getMatchingRule();

        if ( mr == null )
        {
            return null;
        }

        return mr.getComparator();
    }
    
    
    /**
     * @return a copy of the current value
     */
    public ServerBinaryValue clone()
    {
        ServerBinaryValue clone = (ServerBinaryValue)super.clone();
        
        if ( normalizedValue != null )
        {
            clone.normalizedValue = new byte[ normalizedValue.length ];
            System.arraycopy( normalizedValue, 0, clone.normalizedValue, 0, normalizedValue.length );
        }
        
        return clone;
    }


    /**
     * @see Externalizable#writeExternal(ObjectOutput)
     * 
     * We will write the value and the normalized value, only
     * if the normalized value is different.
     * 
     * The data will be stored following this structure :
     * 
     *  [UP value]
     *  [Norm value] (will be null if normValue == upValue)
     */
    public void writeExternal( ObjectOutput out ) throws IOException
    {
        if ( getReference() != null )
        {
            out.writeInt( getReference().length );
            out.write( getReference() );
            
            if ( same )
            {
                // If the normalized value is equal to the UP value,
                // don't save it
                out.writeInt( 0 );
            }
            else
            {
                out.writeInt( normalizedValue.length );
                out.write( normalizedValue );
            }
        }
        else
        {
            out.writeInt( -1 );
        }
        
        out.flush();
    }

    
    /**
     * @see Externalizable#readExternal(ObjectInput)
     */
    public void readExternal( ObjectInput in ) throws IOException, ClassNotFoundException
    {
        if ( in.available() == 0 )
        {
            set( null );
            normalizedValue = null;
        }
        else
        {
            int wrappedLength = in.readInt();
            byte[] wrapped = null;
            
            switch ( wrappedLength )
            {
                case -1 :
                    // No value, no normalized value
                    same = true;
                    setNormalized( false );
                    break;
                    
                case 0 :
                    // Empty value, so is the normalized value
                    wrapped = StringTools.EMPTY_BYTES;
                    normalizedValue = wrapped;
                    setNormalized( true );
                    same = true;
                    break;
                    
                default :
                    wrapped = new byte[wrappedLength];
                    in.readFully( wrapped );
                    
                    int normalizedLength = in.readInt();
                    
                    // The normalized length should be either 0 or N, 
                    // but it can't be -1
                    switch ( normalizedLength )
                    {
                        case -1 :
                            String message = "The normalized value cannot be null when the User Provide value is not";
                            LOG.error(  message  );
                            throw new IOException( message );
                            
                        case 0 :
                            normalizedValue = StringTools.EMPTY_BYTES;
                            same = true;
                            setNormalized( false );
                            break;
                            
                        default :
                            same = false;
                            normalizedValue = new byte[normalizedLength];
                            in.readFully( normalizedValue );
                            setNormalized( true );
                            break;
                    }
                    
                    break;
            }
            
            set( wrapped );
        }
    }
}