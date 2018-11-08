import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

class EntryThumbnail extends React.Component {
    static propTypes = {
        mimeType: PropTypes.object.isRequired,
        entryId: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            thumbnailUri: null
        }
    }

    render(){
        if(this.state.thumbnailUri) return <img src={this.state.thumbnailUri} className="entry-thumbnail"/>;

        if(this.props.mimeType.major==="video") return <FontAwesomeIcon icon="film" size="4x" className="entry-thumbnail"/>;
        if(this.props.mimeType.major==="audio") return <FontAwesomeIcon icon="volume-up" size="4x" className="entry-thumbnail"/>;
        if(this.props.mimeType.major==="image") return <FontAwesomeIcon icon="image" size="4x" className="entry-thumbnail"/>;
        return <FontAwesomeIcon icon="file" size="4x" className="entry-thumbnail"/>;
    }
}

export default EntryThumbnail;