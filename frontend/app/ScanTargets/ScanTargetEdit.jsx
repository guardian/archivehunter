import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';

class ScanTargetEdit extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        entryWasUpdated: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            entry: null
        };

        this.updateBucketname = this.updateBucketname.bind(evt);
        this.formSubmit = this.formSubmit.bind(this);
    }

    componentDidUpdate(oldProps, oldState){
        if(oldProps.entry!==this.props.entry) this.setState({entry: this.props.entry});
    }

    updateBucketname(evt){
        const newEntry = Object.assign({bucketName: evt.target.value});
        this.props.entryWasUpdated(newEntry);   //our state should get updated by bindings in the parent component
        //this.setState({entry: newEntry}, ()=>this.props.entryWasUpdated(newEntry))
    }

    formSubmit(evt){

        evt.preventDefault();
    }

    render(){
        return <form onSubmit={this.formSubmit}>
            <h2>Edit scan target</h2>
            <table>
                <tr>
                    <td>Bucket name</td>
                    <td><input value={this.state.entry.bucketName} onChange={this.updateBucketname}/></td>
                </tr>
                <tr>
                    <td>Enabled</td>
                    <td><input type="checkbox" checked={this.state.entry.enabled} onChange={this.toggleEnabled}/></td>
                </tr>
                <tr>
                    <td>Last Scanned</td>
                    <td><TimestampFormatter relative={true} value={this.state.entry.lastScanned}/></td>
                </tr>
                <tr>
                    <td>Scan Interval</td>
                    <td><TimeIntervalComponent editable={true} value={this.state.entry.scanInterval}/></td>
                </tr>
                <tr>
                    <td>Last Error</td>
                    <td><textarea contentEditable={false} value={this.state.entry.lastError}/></td>
                    <td><button type="button" onClick={this.clearErrorLog}>Clear</button></td>
                </tr>
            </table>
            <input type="submit">Save</input>
            <input type="button">Back</input>
        </form>
    }
}

export default ScanTargetEdit;