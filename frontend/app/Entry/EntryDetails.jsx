import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired
    };

    render(){
        return <div className="entry-details">
            <div style={{display: "inline-flex"}}>
                <EntryPreview entryId={this.props.entry.id}
                              hasProxy={this.props.entry.proxied}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                />
                {/*<ul className="entry-details-list">*/}
                    {/*<li className="entry-details-list">{this.props.entry.bucket}</li>*/}
                    {/*<li className="entry-details-list">{this.props.entry.path}</li>*/}
                    {/*<li className="entry-details-list">{this.props.entry.size}</li>*/}
                    {/*<li className="entry-details-list">{this.props.entry.mimeType.major}/{this.props.entry.mimeType.minor}</li>*/}
                    {/*<li className="entry-details-list">{this.props.entry.storageClass}</li>*/}
                {/*</ul>*/}
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
        </div>
    }
}

export default EntryDetails;