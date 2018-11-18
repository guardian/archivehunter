import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import EntryThumbnail from "../Entry/EntryThumbnail.jsx";

class EntryView extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        itemOpenRequest: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);
        this.entryClicked = this.entryClicked.bind(this);
    }

    entryClicked(){
        if(this.props.itemOpenRequest) this.props.itemOpenRequest(this.props.entry);
    }

    filename(){
        const fnParts = this.props.entry.path.split("/");
        return fnParts.slice(-1);
    }

    classForStorageClass(storageClass){
        if(storageClass==="STANDARD") return "";
        if(storageClass==="STANDARD_IA") return "entry-shallow-archive";
        if(storageClass==="GLACIER") return "entry-deep-archive";
        console.warn("Unrecognised storage class for " + this.props.entry.path + ": " + storageClass);
        return "";
    }

    /*
    ArchiveEntry(id:String, bucket: String, path: String, file_extension: Option[String],
    size: scala.Long, last_modified: ZonedDateTime, etag: String, mimeType: MimeType,
     proxied: Boolean, storageClass:StorageClass, beenDeleted:Boolean=false)

     */
    render(){
        let classList = ["entry-view", this.classForStorageClass(this.props.entry.storageClass)];
        if(this.props.entry.beenDeleted) classList = classList.concat("entry-gone-missing");

        return <div className={classList.join(" ")} onClick={this.entryClicked}>
            <p className="entry-title"><FontAwesomeIcon icon="folder" className="entry-icon"/>{this.filename()}</p>
            <EntryThumbnail mimeType={this.props.entry.mimeType} entryId={this.props.entry.id} fileExtension={this.props.entry.file_extension}/>
            <p className="entry-date"><TimestampFormatter relative={false}
                                                          value={this.props.entry.last_modified}
                                                          formatString="Do MMM YYYY, h:mm a"/></p>
        </div>
    }
}

export default EntryView;