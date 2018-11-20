import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';
import FileSizeView from './FileSizeView.jsx';

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired
    };

    extractFileInfo(fullpath){
        const parts = fullpath.split("/");
        const len = parts.length;
        if(len===0){
            return {
                filename: parts[0],
                filepath: ""
            }
        }

        return {
            filename: parts[len-1],
            filepath: parts.slice(0,len-1).join("/")
        }
    }

    render(){
        if(!this.props.entry){
            return <div className="entry-details">
            </div>
        }
        const fileinfo = this.extractFileInfo(this.props.entry.path);

        return <div className="entry-details">
                <EntryPreview entryId={this.props.entry.id}
                              hasProxy={this.props.entry.proxied}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                />

                <table className="metadata-table">
                    <tbody>
                    <tr>
                        <td className="metadata-heading">Name</td>
                        <td className="metadata-entry">{fileinfo.filename}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File path</td>
                        <td className="metadata-entry">{fileinfo.filepath}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Catalogue</td>
                        <td className="metadata-entry">{this.props.entry.bucket}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File size</td>
                        <td className="metadata-entry"><FileSizeView rawSize={this.props.entry.size}/></td>
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