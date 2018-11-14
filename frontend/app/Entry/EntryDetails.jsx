import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired
    };

    render(){
        if(!this.props.entry){
            return <div className="entry-details">
            </div>
        }
        return <div className="entry-details">
                <EntryPreview entryId={this.props.entry.id}
                              hasProxy={this.props.entry.proxied}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                />

                <table className="metadata-table">
                    <tbody>
                    <tr>
                        <td className="metadata-heading">File path</td>
                        <td className="metadata-entry">{this.props.entry.path}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Catalogue</td>
                        <td className="metadata-entry">{this.props.entry.bucket}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File size</td>
                        <td className="metadata-entry">{this.props.entry.size}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Data type</td>
                        <td className="metadata-entry">{this.props.entry.mimeType.major}/{this.props.entry.mimeType.minor}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Storage class</td>
                        <td className="metadata-entry">{this.props.entry.storageClass}</td>
                    </tr>
                    </tbody>
                </table>
        </div>
    }
}

export default EntryDetails;