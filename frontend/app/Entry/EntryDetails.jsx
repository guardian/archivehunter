import React from 'react';
import PropTypes from 'prop-types';

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.objects.isRequired
    };

    render(){
        return <div className="entry-details">
            <div style={{display: "inline-block", width: "800px", height:"450px"}}>
                <EntryPreview entryId={this.props.entry.id} hasProxy={this.props.entry.proxied}/>
            </div>
            <div style={{display: "inline-block"}}>
                <ul className="entry-details-list">
                    <li className="entry-details-list">{this.props.entry.bucket}</li>
                    <li className="entry-details-list">{this.props.entry.path}</li>
                    <li className="entry-details-list">{this.props.entry.size}</li>
                    <li className="entry-details-list">{this.props.entry.mimeType}</li>
                    <li className="entry-details-list">{this.props.entry.storageClass}</li>
                </ul>
            </div>
        </div>
    }
}

export default EntryDetails;