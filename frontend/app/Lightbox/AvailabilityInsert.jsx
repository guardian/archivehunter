import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import axios from 'axios';
import FileDownload from 'js-file-download';

class AvailabilityInsert extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired,
        availableUntil: PropTypes.string.isRequired,
        hidden: PropTypes.bool.isRequired,
        fileId: PropTypes.string.isRequired,
        fileNameOnly: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError:null,
            downloadUrl: null
        };
        this.doDownload = this.doDownload.bind(this);
    }

    doDownload(){
        this.setState({loading: true}, ()=>axios.get("/api/download/" + this.props.fileId)
            .then(response=>{
                this.setState({loading: false, lastError: null, downloadUrl: response.data.entry})
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err})
            })
        );
    }

    timeLabel(){
        if(this.props.availableUntil){
            return <span>for <TimestampFormatter relative={true} value={this.props.availableUntil}/></span>
        } else {
            return <span>indefinitely</span>
        }
    }

    render(){
        return <div style={{display: this.props.hidden ? "none":"block"}} className="centered">
            <p className="centered no-spacing">Available {this.timeLabel()}</p>
            <p className="centered no-spacing"><a onClick={this.doDownload} style={{cursor: "pointer"}}>Download</a></p>
            {
                this.state.downloadUrl ?
                    <iframe src={this.state.downloadUrl} style={{visibility: "none",display:"none"}}/> : <span/>
            }
        </div>
    }
}

export default AvailabilityInsert;