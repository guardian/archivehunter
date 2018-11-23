import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

class EntryPreviewSwitcher extends React.Component {
    static propTypes = {
       availableTypes: PropTypes.array.isRequired,
        typeSelected: PropTypes.func.isRequired
    };

    static expectedTypes = [
        "THUMBNAIL",
        "POSTER",
        "VIDEO",
        "AUDIO"
    ];

    render(){
        return <span className="centered entry-preview-switcher">
            <FontAwesomeIcon icon="image"
                             size="2x"
                             className={this.props.availableTypes.includes("THUMBNAIL") ? "image-button-enabled" : "image-button-disabled"}
                             onClick={()=>this.props.typeSelected("THUMBNAIL")}/>
            <FontAwesomeIcon icon="film"
                             size="2x"
                             className={this.props.availableTypes.includes("VIDEO") ? "image-button-enabled" : "image-button-disabled"}
                             onClick={()=>this.props.typeSelected("VIDEO")}/>
            <FontAwesomeIcon icon="volume-up"
                             size="2x"
                             className={this.props.availableTypes.includes("AUDIO") ? "image-button-enabled" : "image-button-disabled"}
                             onClick={()=>this.props.typeSelected("AUDIO")}/>
        </span>
    }
}

export default EntryPreviewSwitcher;